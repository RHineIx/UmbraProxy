package main

import (
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"
)

func TestCoreLogBufferReportsOverflowWithoutMergingDuplicates(t *testing.T) {
	b := newCoreLogBuffer(3)
	for i := 0; i < 5; i++ {
		b.append(coreLogEntry{Level: "INFO", Message: "same message"})
	}
	batch := b.drain()
	if batch.Dropped != 2 || len(batch.Entries) != 3 {
		t.Fatalf("unexpected retention: %+v", batch)
	}
	for i, entry := range batch.Entries {
		if entry.Sequence != uint64(i+3) {
			t.Fatalf("wrong order: %+v", batch)
		}
	}
	empty := b.drain()
	if empty.Dropped != 0 || len(empty.Entries) != 0 {
		t.Fatal("drain returned duplicate events")
	}
	b.append(coreLogEntry{Message: "after drain"})
	if b.drain().Entries[0].Sequence != 6 {
		t.Fatal("sequence reset after drain")
	}
}

func TestCoreLogBufferClearDropsQueuedEvents(t *testing.T) {
	b := newCoreLogBuffer(2)
	b.append(coreLogEntry{Message: "old"})
	b.clear()
	b.append(coreLogEntry{Message: "new"})
	batch := b.drain()
	if len(batch.Entries) != 1 || batch.Entries[0].Message != "new" || batch.Entries[0].Sequence != 2 {
		t.Fatalf("%+v", batch)
	}
}

func TestCoreLogBufferConcurrentWritersAndDrains(t *testing.T) {
	b := newCoreLogBuffer(64)
	var writers sync.WaitGroup
	for i := 0; i < 8; i++ {
		writers.Add(1)
		go func() {
			defer writers.Done()
			for j := 0; j < 250; j++ {
				b.append(coreLogEntry{Message: "event"})
			}
		}()
	}
	done := make(chan struct{})
	go func() { writers.Wait(); close(done) }()
	var total, last uint64
	for {
		batch := b.drain()
		total += uint64(len(batch.Entries)) + batch.Dropped
		for _, entry := range batch.Entries {
			if entry.Sequence <= last {
				t.Fatal("events replayed or reordered")
			}
			last = entry.Sequence
		}
		select {
		case <-done:
			batch = b.drain()
			total += uint64(len(batch.Entries)) + batch.Dropped
			if total != 2000 {
				t.Fatalf("events lost without accounting: %d", total)
			}
			return
		default:
			time.Sleep(time.Millisecond)
		}
	}
}

func TestCoreLogSanitizationAndUnicodeLimit(t *testing.T) {
	for _, message := range []string{"secret=private-key&port=1443", "password: private-key", "Authorization: Bearer private-key", "TOKEN=private-key"} {
		clean, _ := sanitizeCoreLog(message)
		if strings.Contains(clean, "private-key") || !strings.Contains(clean, "[REDACTED]") {
			t.Fatalf("credential leaked: %s", clean)
		}
	}
	long, truncated := sanitizeCoreLog(strings.Repeat("س", 5000))
	if !truncated || len(long) > maxLogMessageBytes || !utf8.ValidString(long) {
		t.Fatal("invalid Unicode truncation")
	}
}

func TestCoreLogWriterCapturesActualLevelTimeAndSource(t *testing.T) {
	coreLogs.clear()
	before := time.Now().UnixMilli()
	logWarn.Print("message contains [ERROR] but its event is WARN")
	encoded := encodeCoreLogs()
	var batch coreLogBatch
	if err := json.Unmarshal(encoded, &batch); err != nil {
		t.Fatal(err)
	}
	if len(batch.Entries) != 1 {
		t.Fatalf("%s", encoded)
	}
	e := batch.Entries[0]
	if e.Level != "WARN" || e.Source != "Core" || e.Timestamp < before || e.Timestamp > time.Now().UnixMilli() || e.PID <= 0 {
		t.Fatalf("incorrect event metadata: %+v", e)
	}
	if strings.Contains(string(encodeCoreLogs()), "message contains") {
		t.Fatal("event replayed")
	}
}
