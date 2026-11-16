package main

import (
	"encoding/json"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const coreLogCapacity = 512
const maxLogMessageBytes = 4096

type coreLogEntry struct {
	Sequence  uint64 `json:"sequence"`
	Timestamp int64  `json:"timestamp_ms"`
	Level     string `json:"level"`
	Source    string `json:"source"`
	Message   string `json:"message"`
	PID       int    `json:"pid"`
	Truncated bool   `json:"truncated"`
}

type coreLogBatch struct {
	Entries []coreLogEntry `json:"entries"`
	Dropped uint64         `json:"dropped"`
}

// This queue is independent of Android logcat and survives opening/closing the
// viewer. Overflow is reported explicitly, rather than silently dropping lines.
type coreLogBuffer struct {
	mu                sync.Mutex
	entries           []coreLogEntry
	head, size        int
	sequence, dropped uint64
}

func newCoreLogBuffer(capacity int) *coreLogBuffer {
	return &coreLogBuffer{entries: make([]coreLogEntry, capacity)}
}

var coreLogs = newCoreLogBuffer(coreLogCapacity)
var credentialInLog = regexp.MustCompile(`(?i)((?:secret|secret_key|password|token|authorization)\s*[:=]\s*)[^\s&"']+`)
var authorizationInLog = regexp.MustCompile(`(?i)(authorization\s*[:=]\s*)(?:Bearer|Basic)\s+[^\s&"']+`)

func sanitizeCoreLog(message string) (string, bool) {
	message = authorizationInLog.ReplaceAllString(strings.TrimRight(message, "\r\n"), "${1}[REDACTED]")
	message = credentialInLog.ReplaceAllString(message, "${1}[REDACTED]")
	if len(message) <= maxLogMessageBytes {
		return message, false
	}
	end := maxLogMessageBytes
	for !utf8.ValidString(message[:end]) {
		end--
	}
	return message[:end], true
}

func (b *coreLogBuffer) append(entry coreLogEntry) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.sequence++
	entry.Sequence = b.sequence
	if b.size == len(b.entries) {
		b.head = (b.head + 1) % len(b.entries)
		b.size--
		b.dropped++
	}
	b.entries[(b.head+b.size)%len(b.entries)] = entry
	b.size++
}

func (b *coreLogBuffer) drain() coreLogBatch {
	b.mu.Lock()
	defer b.mu.Unlock()
	batch := coreLogBatch{Entries: make([]coreLogEntry, b.size), Dropped: b.dropped}
	for i := range batch.Entries {
		index := (b.head + i) % len(b.entries)
		batch.Entries[i] = b.entries[index]
		b.entries[index] = coreLogEntry{}
	}
	b.head, b.size, b.dropped = 0, 0, 0
	return batch
}

func (b *coreLogBuffer) clear() { _ = b.drain() }

func recordCoreLog(level, message string) string {
	message, truncated := sanitizeCoreLog(message)
	coreLogs.append(coreLogEntry{
		Timestamp: time.Now().UnixMilli(), Level: level, Source: "Core",
		Message: message, PID: os.Getpid(), Truncated: truncated,
	})
	return message
}

func encodeCoreLogs() []byte {
	// This schema contains only JSON-safe strings, integers and booleans.
	encoded, _ := json.Marshal(coreLogs.drain())
	return encoded
}
