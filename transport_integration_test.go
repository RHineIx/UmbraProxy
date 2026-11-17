package main

import (
	"context"
	"crypto/x509"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func trustTestServer(t *testing.T, server *httptest.Server) {
	t.Helper()
	previous := tlsConfigPool
	config := previous.Clone()
	config.RootCAs = x509.NewCertPool()
	config.RootCAs.AddCert(server.Certificate())
	tlsConfigPool = config
	t.Cleanup(func() { tlsConfigPool = previous })
}

func TestEstablishedWSOutlivesConnectionAttemptDeadline(t *testing.T) {
	release := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, writer, err := w.(http.Hijacker).Hijack()
		if err != nil {
			return
		}
		defer conn.Close()
		writer.WriteString(upgradeResponse(r.Header.Get("Sec-WebSocket-Key"), ""))
		writer.Flush()
		<-release
		conn.Write([]byte{0x82, 2, 'o', 'k'})
	}))
	defer server.Close()
	trustTestServer(t, server)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	ws, err := wsConnectAddress(ctx, server.Listener.Addr().String(), "example.com", "/apiws", time.Second)
	if err != nil {
		close(release)
		t.Fatal(err)
	}
	defer ws.Close()
	cancel()
	close(release)
	ws.conn.SetReadDeadline(time.Now().Add(time.Second))
	data, err := ws.Recv()
	if err != nil || string(data) != "ok" {
		t.Fatalf("connection attempt canceled an established relay: %q %v", data, err)
	}
}

func TestUpgradeTimeoutIsCanceledPromptly(t *testing.T) {
	release := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-release }))
	defer server.Close()
	defer close(release)
	trustTestServer(t, server)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	time.AfterFunc(80*time.Millisecond, cancel)
	start := time.Now()
	ws, err := wsConnectAddress(ctx, server.Listener.Addr().String(), "example.com", "/apiws", 5*time.Second)
	if ws != nil {
		ws.Close()
	}
	if err == nil || time.Since(start) > time.Second {
		t.Fatalf("cancellation did not bound upgrade: %v", err)
	}
}

func TestLifecycleHealthDoesNotReportListenerAsRoute(t *testing.T) {
	cfproxyMu.Lock()
	previous := cfproxyUserDomain
	cfproxyUserDomain = "test.example.com"
	cfproxyMu.Unlock()
	defer func() { stopProxy(); cfproxyMu.Lock(); cfproxyUserDomain = previous; cfproxyMu.Unlock() }()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	if code := startProxy("127.0.0.1", port, "", "00112233445566778899aabbccddeeff", false); code != 0 {
		t.Fatalf("startup: %d", code)
	}
	if s := currentHealth.Load().snapshot(); !s.Listening || s.Status != "waiting" {
		t.Fatalf("startup health: %+v", s)
	}
	stopProxy()
	if s := currentHealth.Load().snapshot(); s.Listening || s.Status != "stopped" {
		t.Fatalf("shutdown health: %+v", s)
	}
}
