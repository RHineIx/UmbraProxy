package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type proxyHealthKey struct{}

type healthSnapshot struct {
	Status       string `json:"status"`
	Listening    bool   `json:"listening"`
	ActiveRelays int    `json:"active_relays"`
	Route        string `json:"route"`
	LastError    string `json:"last_error"`
	CheckedAt    int64  `json:"checked_at"`
}

// Each run owns its own state, so canceled probes cannot overwrite a new run.
type proxyHealth struct {
	routes           *routeFailureCache
	mu               sync.Mutex
	probeMu          sync.Mutex
	listening        bool
	checking         bool
	activeRelays     int
	route, lastError string
	checkedAt        time.Time
	available        bool
}

var currentHealth atomic.Pointer[proxyHealth]

func healthFor(ctx context.Context) *proxyHealth {
	h, _ := ctx.Value(proxyHealthKey{}).(*proxyHealth)
	return h
}

func routeCacheFor(ctx context.Context) *routeFailureCache {
	h := healthFor(ctx)
	if h == nil {
		return routeFailures
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.routes == nil {
		h.routes = newRouteFailureCache(time.Now)
	}
	return h.routes
}

func (h *proxyHealth) snapshot() healthSnapshot {
	if h == nil {
		return healthSnapshot{Status: "stopped"}
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	result := healthSnapshot{Listening: h.listening, ActiveRelays: h.activeRelays, Route: h.route, LastError: h.lastError}
	if !h.checkedAt.IsZero() {
		result.CheckedAt = h.checkedAt.UnixMilli()
	}
	switch {
	case !h.listening:
		result.Status = "stopped"
	case h.activeRelays > 0:
		result.Status = "available"
	case h.checking:
		result.Status = "checking"
	case h.checkedAt.IsZero():
		result.Status = "waiting"
	case h.available && time.Since(h.checkedAt) < 45*time.Second:
		result.Status = "available"
	case h.available:
		result.Status = "waiting"
	default:
		result.Status = "unavailable"
	}
	return result
}

func (h *proxyHealth) result(route string, err error) {
	if h == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	h.checkedAt = time.Now()
	h.available = err == nil
	if err != nil {
		h.lastError, _ = sanitizeCoreLog(err.Error())
	} else {
		h.lastError = ""
		h.route = route
	}
}

func relayHealth(ctx context.Context, route string) func() {
	h := healthFor(ctx)
	if h == nil {
		return func() {}
	}
	h.mu.Lock()
	h.activeRelays++
	h.available = true
	h.checkedAt = time.Now()
	h.route = route
	h.lastError = ""
	h.mu.Unlock()
	return func() { h.mu.Lock(); h.activeRelays--; h.mu.Unlock() }
}

func encodeHealth(h *proxyHealth) []byte {
	data, _ := json.Marshal(h.snapshot())
	return data
}

// Probe transport reachability for DC2, not Telegram account/authentication state.
// WS success includes TLS certificate and HTTP upgrade verification; TCP success
// only proves that the DC port can be reached. Never send user traffic or a secret.
func probeRoute(ctx context.Context) (string, error) {
	const dc = 2
	if target, configured := resolveConfiguredTarget(dc, false); configured {
		if ws, _ := connectDirectWS(ctx, target, wsDomains(dc, false), 4); ws != nil {
			ws.Close()
			return "WS DC2", nil
		}
	}
	cfCtx, cancelCF := routeStageContext(ctx, cfConnectBudget, tcpConnectBudget)
	ws, domain, err := connectCfproxy(cfCtx, dc, false)
	cancelCF()
	if err == nil {
		ws.Close()
		return "CF DC2: " + domain, nil
	}
	target := resolveFallbackTarget(dc, false)
	key := tcpRouteKey(target)
	if target != "" {
		tcpCtx, cancelTCP := routeStageContext(ctx, tcpConnectBudget, 0)
		defer cancelTCP()
		if !routeCacheFor(ctx).begin(tcpCtx, key) {
			return "", fmt.Errorf("DC2 routes failed or are cooling down")
		}
		conn, err := (&net.Dialer{Timeout: tcpConnectBudget}).DialContext(tcpCtx, "tcp", net.JoinHostPort(target, "443"))
		routeCacheFor(ctx).record(tcpCtx, key, err)
		if err == nil {
			conn.Close()
			return "TCP DC2", nil
		}
	}
	return "", fmt.Errorf("DC2 routes failed, timed out, or are cooling down; inspect Core logs")
}

func probeUpstreamHealth() []byte {
	globalMu.Lock()
	ctx, h := globalCtx, currentHealth.Load()
	globalMu.Unlock()
	if ctx == nil || h == nil || !h.snapshot().Listening || !h.probeMu.TryLock() {
		return encodeHealth(h)
	}
	defer h.probeMu.Unlock()
	// Active relays provide passive evidence without extra network probes.
	h.mu.Lock()
	if h.activeRelays > 0 {
		h.mu.Unlock()
		return encodeHealth(h)
	}
	h.checking = true
	h.mu.Unlock()
	defer func() { h.mu.Lock(); h.checking = false; h.mu.Unlock() }()
	probeCtx, cancel := context.WithTimeout(context.WithValue(ctx, proxyHealthKey{}, h), upstreamConnectBudget)
	defer cancel()
	route, err := probeRoute(probeCtx)
	if ctx.Err() == nil {
		h.result(route, err)
		if err != nil {
			logDebug.Printf("Health check: %s", err)
		}
	}
	// Clear before encoding so callers receive the completed result.
	h.mu.Lock()
	h.checking = false
	h.mu.Unlock()
	return encodeHealth(h)
}
