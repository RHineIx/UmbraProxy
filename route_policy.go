package main

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

const (
	upstreamConnectBudget = 15 * time.Second
	directConnectBudget   = 4 * time.Second
	cfConnectBudget       = 8 * time.Second
	tcpConnectBudget      = 3 * time.Second
	maxRouteCandidates    = 32
)

type routeFailure struct {
	strikes int
	until   time.Time
	owner   context.Context
}

// Shared by client connections and health probes; cleared after a network restart.
type routeFailureCache struct {
	mu      sync.Mutex
	entries map[string]routeFailure
	now     func() time.Time
}

func newRouteFailureCache(now func() time.Time) *routeFailureCache {
	return &routeFailureCache{entries: make(map[string]routeFailure), now: now}
}

var routeFailures = newRouteFailureCache(time.Now)

func (c *routeFailureCache) allowed(key string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry := c.entries[key]
	return entry.owner == nil && !c.now().Before(entry.until)
}

func (c *routeFailureCache) makeRoom(key string, now time.Time) bool {
	if _, exists := c.entries[key]; exists {
		return true
	}
	if len(c.entries) < 512 {
		return true
	}
	for k, v := range c.entries {
		if v.owner == nil && !now.Before(v.until) {
			delete(c.entries, k)
		}
	}
	if len(c.entries) < 512 {
		return true
	}
	for k, v := range c.entries {
		if v.owner == nil {
			delete(c.entries, k)
			return true
		}
	}
	return false
}

func (c *routeFailureCache) begin(ctx context.Context, key string) bool {
	if ctx.Err() != nil {
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	entry := c.entries[key]
	if entry.owner != nil || c.now().Before(entry.until) || !c.makeRoom(key, c.now()) {
		return false
	}
	// Healthy/new endpoints keep normal concurrency. Only one caller probes a
	// previously failed endpoint after its cooldown (a half-open circuit).
	if entry.strikes == 0 {
		return true
	}
	entry.owner = ctx
	c.entries[key] = entry
	return true
}

func (c *routeFailureCache) record(ctx context.Context, key string, err error) {
	// Losing a parallel race or stopping the proxy is not an endpoint failure.
	c.mu.Lock()
	defer c.mu.Unlock()
	entry := c.entries[key]
	// A late initial-write failure must not release another caller's reservation.
	if entry.owner != nil && entry.owner != ctx {
		return
	}
	entry.owner = nil
	if errors.Is(err, context.Canceled) || errors.Is(ctx.Err(), context.Canceled) {
		if entry.strikes == 0 {
			delete(c.entries, key)
		} else {
			c.entries[key] = entry
		}
		return
	}

	if err == nil {
		delete(c.entries, key)
		return
	}
	now := c.now()
	if now.Sub(entry.until) > time.Minute {
		entry.strikes = 0
	}
	if entry.strikes < 6 {
		entry.strikes++
	}
	delay := 2 * time.Second * time.Duration(1<<(entry.strikes-1))
	if delay > time.Minute {
		delay = time.Minute
	}
	entry.until = now.Add(delay)
	if !c.makeRoom(key, now) {
		return
	}

	c.entries[key] = entry
}

func (c *routeFailureCache) clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries = make(map[string]routeFailure)
}

func cfRouteKey(dc int, domain string) string     { return fmt.Sprintf("cf:%d:%s", dc, domain) }
func directRouteKey(target, domain string) string { return "ws:" + target + ":" + domain }
func tcpRouteKey(target string) string            { return "tcp:" + target }

// Leave time for TCP instead of exhausting the complete deadline on WS/CF.
func routeStageContext(parent context.Context, maximum, reserve time.Duration) (context.Context, context.CancelFunc) {
	budget := maximum
	if deadline, ok := parent.Deadline(); ok {
		if available := time.Until(deadline) - reserve; available < budget {
			budget = available
		}
	}
	if budget < 0 {
		budget = 0
	}
	return context.WithTimeout(parent, budget)
}

func sendWSInit(ctx context.Context, ws *RawWebSocket, init []byte) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	stop := context.AfterFunc(ctx, func() { ws.Close() })
	err := ws.Send(init)
	if !stop() && err == nil {
		err = ctx.Err()
	}
	return err
}
