package main

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"
)

func TestHealthSeparatesListenerProbeAndRelayStates(t *testing.T) {
	h := &proxyHealth{listening: true}
	if h.snapshot().Status != "waiting" {
		t.Fatal("listener incorrectly implies available upstream")
	}
	h.checking = true
	if h.snapshot().Status != "checking" {
		t.Fatal("missing checking state")
	}
	h.checking = false
	h.result("CF DC2", nil)
	if h.snapshot().Status != "available" {
		t.Fatal("successful check not published")
	}
	h.checkedAt = time.Now().Add(-time.Minute)
	if h.snapshot().Status != "waiting" {
		t.Fatal("stale check remained available")
	}
	ctx := context.WithValue(context.Background(), proxyHealthKey{}, h)
	done := relayHealth(ctx, "TCP DC4")
	h.result("", errors.New("other DC failed"))
	if s := h.snapshot(); s.Status != "available" || s.ActiveRelays != 1 {
		t.Fatal("probe failure hid active relay")
	}
	done()
	if h.snapshot().Status != "unavailable" {
		t.Fatal("failure was not exposed after relay closed")
	}
	h.result("WS DC2", nil)
	if h.snapshot().LastError != "" {
		t.Fatal("success did not clear failure")
	}
	h.listening = false
	if h.snapshot().Status != "stopped" {
		t.Fatal("closed listener remained ready")
	}
}

func TestCanceledRunCannotOverwriteNewHealth(t *testing.T) {
	previous := currentHealth.Load()
	t.Cleanup(func() { currentHealth.Store(previous) })
	old := &proxyHealth{listening: true}
	next := &proxyHealth{listening: true}
	currentHealth.Store(next)
	old.result("old route", errors.New("old failure"))
	oldContext := context.WithValue(context.Background(), proxyHealthKey{}, old)
	newContext := context.WithValue(context.Background(), proxyHealthKey{}, next)
	routeCacheFor(oldContext).record(oldContext, "route", errors.New("old route failure"))
	if !routeCacheFor(newContext).allowed("route") {
		t.Fatal("old run contaminated new route cooldowns")
	}
	var decoded healthSnapshot
	if err := json.Unmarshal(encodeHealth(currentHealth.Load()), &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Status != "waiting" || decoded.LastError != "" || decoded.Route != "" {
		t.Fatal("old run contaminated current health")
	}
}
