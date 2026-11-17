package main

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"
)

func TestRouteCooldownBackoffResetAndCancellation(t *testing.T) {
	now := time.Now()
	c := newRouteFailureCache(func() time.Time { return now })
	ctx := context.Background()
	err := errors.New("timeout")
	for _, delay := range []time.Duration{2, 4, 8, 16, 32, 60, 60} {
		c.record(ctx, "route", err)
		if c.allowed("route") {
			t.Fatal("failed route was immediately retried")
		}
		if got := c.entries["route"].until.Sub(now); got != delay*time.Second {
			t.Fatalf("cooldown %v", got)
		}
		now = now.Add(delay * time.Second)
		if !c.allowed("route") {
			t.Fatal("route did not become eligible")
		}
	}
	c.record(ctx, "route", nil)
	c.record(ctx, "route", err)
	if c.entries["route"].strikes != 1 {
		t.Fatal("success did not reset failures")
	}
	canceled, cancel := context.WithCancel(ctx)
	cancel()
	c.record(canceled, "canceled", err)
	c.record(ctx, "canceled2", context.Canceled)
	if !c.allowed("canceled") || !c.allowed("canceled2") {
		t.Fatal("cancellation penalized an endpoint")
	}
	if !c.allowed("other") {
		t.Fatal("unrelated route penalized")
	}
	c.clear()
	if !c.allowed("route") {
		t.Fatal("network restart retained cooldown")
	}
}

func TestRouteCacheAndCandidateListAreBounded(t *testing.T) {
	c := newRouteFailureCache(time.Now)
	var names []string
	for i := 0; i < 1000; i++ {
		key := fmt.Sprintf("route-%d", i)
		c.record(context.Background(), key, errors.New("failure"))
		names = append(names, fmt.Sprintf("route%d.example.com", i))
	}
	if len(c.entries) > 512 {
		t.Fatal("unbounded route cache")
	}
	if len(mergeCfproxyDomains(names)) != maxRouteCandidates {
		t.Fatal("unbounded candidate list")
	}
}

func TestOnlyOneAttemptPerEndpointAndCanceledAttemptReleasesIt(t *testing.T) {
	now := time.Now()
	c := newRouteFailureCache(func() time.Time { return now })
	ctx, cancel := context.WithCancel(context.Background())
	if !c.begin(ctx, "healthy") || !c.begin(context.Background(), "healthy") {
		t.Fatal("healthy endpoint concurrency was restricted")
	}
	c.record(ctx, "route", errors.New("failure"))
	now = now.Add(2 * time.Second)
	if !c.begin(ctx, "route") || c.begin(ctx, "route") || c.allowed("route") {
		t.Fatal("duplicate concurrent endpoint attempts")
	}
	c.record(context.Background(), "route", errors.New("late failure from another caller"))
	if c.allowed("route") {
		t.Fatal("another caller released a reservation")
	}
	cancel()
	c.record(ctx, "route", context.Canceled)
	if !c.begin(context.Background(), "route") {
		t.Fatal("canceled attempt leaked its reservation")
	}
	c.record(context.Background(), "route", nil)
	if !c.allowed("route") {
		t.Fatal("successful attempt leaked its reservation")
	}
}

func TestRouteStagesReserveTimeForTCP(t *testing.T) {
	parent, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	child, stop := routeStageContext(parent, time.Second, 300*time.Millisecond)
	defer stop()
	pd, _ := parent.Deadline()
	cd, _ := child.Deadline()
	if reserve := pd.Sub(cd); reserve < 290*time.Millisecond || reserve > 310*time.Millisecond {
		t.Fatalf("TCP reserve: %v", reserve)
	}
	noTime, stop2 := routeStageContext(parent, time.Second, 2*time.Second)
	defer stop2()
	if noTime.Err() == nil {
		t.Fatal("ran stage without reserved time")
	}
	cancel()
	if child.Err() == nil {
		t.Fatal("stage ignored cancellation")
	}
}

func TestCf429StrikesSurviveCooldownExpiry(t *testing.T) {
	clearCfproxy429Cooldowns()
	t.Cleanup(clearCfproxy429Cooldowns)
	cfproxy429Mu.Lock()
	cfproxy429StateByDomain["test.example.com"] = cfproxy429State{until: time.Now().Add(-time.Millisecond), strikes: 2}
	cfproxy429Mu.Unlock()
	if cfproxy429CooldownRemaining("test.example.com") != 0 {
		t.Fatal("cooldown not expired")
	}
	markCfproxy429Cooldown(context.Background(), "test.example.com", &WsHandshakeError{StatusCode: 429})
	if remaining := cfproxy429CooldownRemaining("test.example.com"); remaining < 170*time.Second {
		t.Fatalf("429 backoff reset unexpectedly: %v", remaining)
	}
	clearCfproxy429Cooldown(context.Background(), "test.example.com")
	markCfproxy429Cooldown(context.Background(), "test.example.com", &WsHandshakeError{StatusCode: 429, Headers: map[string]string{"retry-after": "5"}})
	if remaining := cfproxy429CooldownRemaining("test.example.com"); remaining < 4*time.Second || remaining > 5*time.Second {
		t.Fatalf("Retry-After ignored: %v", remaining)
	}
	canceled, cancel := context.WithCancel(context.Background())
	cancel()
	markCfproxy429Cooldown(canceled, "test.example.com", &WsHandshakeError{StatusCode: 429})
	clearCfproxy429Cooldown(canceled, "test.example.com")
	if remaining := cfproxy429CooldownRemaining("test.example.com"); remaining < 4*time.Second || remaining > 5*time.Second {
		t.Fatal("late canceled attempt changed cooldown")
	}
}
