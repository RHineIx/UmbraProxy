package main

import (
	"bufio"
	"bytes"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type observedConn struct {
	net.Conn
	closes atomic.Int32
}

func (c *observedConn) Close() error {
	c.closes.Add(1)
	return c.Conn.Close()
}

func TestWebSocketClosesTransportAfterReadOrWriteError(t *testing.T) {
	for _, operation := range []string{"read", "write"} {
		t.Run(operation, func(t *testing.T) {
			client, peer := net.Pipe()
			conn := &observedConn{Conn: client}
			t.Cleanup(func() { client.Close(); peer.Close() })
			ws := &RawWebSocket{conn: conn, bufReader: bufio.NewReader(conn)}
			peer.Close()
			var err error
			if operation == "read" {
				_, err = ws.Recv()
			} else {
				err = ws.Send([]byte("payload"))
			}
			if err == nil {
				t.Fatal("expected transport failure")
			}
			ws.Close()
			ws.Close()
			if got := conn.closes.Load(); got != 1 {
				t.Fatalf("transport closed %d times", got)
			}
		})
	}
}

func TestWebSocketCloseInterruptsBlockedWrite(t *testing.T) {
	client, peer := net.Pipe()
	t.Cleanup(func() { client.Close(); peer.Close() })
	ws := &RawWebSocket{conn: client, bufReader: bufio.NewReader(client)}
	done := make(chan error, 1)
	go func() { done <- ws.Send([]byte("blocked")) }()
	ws.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("expected write to fail")
		}
	case <-time.After(time.Second):
		t.Fatal("Close failed to interrupt the writer")
	}
}

func upgradeResponse(key, extra string) string {
	digest := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	return "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: " + base64.StdEncoding.EncodeToString(digest[:]) + "\r\n" + extra + "\r\n"
}

func TestWebSocketUpgradeValidation(t *testing.T) {
	key := "dGhlIHNhbXBsZSBub25jZQ=="
	valid := upgradeResponse(key, "Sec-WebSocket-Protocol: binary\r\n")
	for _, test := range []struct {
		name, response string
		valid          bool
	}{
		{"valid", valid, true},
		{"no protocol selected", upgradeResponse(key, ""), true},
		{"wrong challenge", strings.Replace(valid, "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", "invalid", 1), false},
		{"missing upgrade", strings.Replace(valid, "Upgrade: websocket\r\n", "", 1), false},
		{"wrong connection token", strings.Replace(valid, "keep-alive, Upgrade", "keep-alive", 1), false},
		{"wrong subprotocol", strings.Replace(valid, "Protocol: binary", "Protocol: text", 1), false},
		{"unrequested compression", upgradeResponse(key, "Sec-WebSocket-Extensions: permessage-deflate\r\n"), false},
		{"oversized line", "HTTP/1.1 101 Switching Protocols\r\nX: " + strings.Repeat("a", 8192) + "\r\n\r\n", false},
		{"truncated", "HTTP/1.1 101 Switching Protocols\r\n", false},
	} {
		t.Run(test.name, func(t *testing.T) {
			err := readWebSocketUpgrade(bufio.NewReader(strings.NewReader(test.response)), key)
			if (err == nil) != test.valid {
				t.Fatalf("valid=%t, error=%v", test.valid, err)
			}
		})
	}
}

func TestWebSocketUpgradePreservesFrameBytesAndRetryAfter(t *testing.T) {
	key := "challenge"
	reader := bufio.NewReader(strings.NewReader(upgradeResponse(key, "") + "frame"))
	if err := readWebSocketUpgrade(reader, key); err != nil {
		t.Fatal(err)
	}
	remaining, _ := io.ReadAll(reader)
	if string(remaining) != "frame" {
		t.Fatalf("lost buffered frame: %q", remaining)
	}
	err := readWebSocketUpgrade(bufio.NewReader(strings.NewReader("HTTP/1.1 429 Too Many Requests\r\nRetry-After: 10\r\n\r\n")), key)
	var status *WsHandshakeError
	if !errors.As(err, &status) || status.StatusCode != 429 || retryAfterDelay(err) != 10*time.Second {
		t.Fatalf("lost structured status or headers: %v", err)
	}
}

func TestParseDCConfiguration(t *testing.T) {
	got, err := parseCIDRPool(" 2:149.154.167.51, -2:2001:db8::1 ")
	if err != nil || got[2] != "149.154.167.51" || got[-2] != "2001:db8::1" {
		t.Fatalf("%v, %v", got, err)
	}
	for _, value := range []string{"2:example.com", "2:not-an-ip", "0:127.0.0.1", "9:127.0.0.1", "2", "2:127.0.0.1,", "2:127.0.0.1,2:127.0.0.2"} {
		if _, err := parseCIDRPool(value); err == nil {
			t.Errorf("accepted %q", value)
		}
	}
}

func TestCustomDomainsAreNotDecodedAsBundledDomains(t *testing.T) {
	for _, domain := range []string{"proxy.example.com", "proxy.example.net", "proxy.example.co.uk"} {
		if got := normalizeCfDomain(domain); got != domain {
			t.Errorf("%q became %q", domain, got)
		}
	}
	for _, domain := range []string{"a..co.uk", "-bad.co.uk", "https://example.com", "example.com/path", "x\r\nInjected: value.co.uk"} {
		if got := normalizeCfDomain(domain); got != "" {
			t.Errorf("accepted %q as %q", domain, got)
		}
	}
	if len(defaultCfproxyDomains()) != len(cfproxyEnc) {
		t.Fatal("bundled encoded domains stopped decoding")
	}
}

func TestOversizedMTProtoPacketDoesNotGrowBuffer(t *testing.T) {
	for _, proto := range []int{protoAbridged, protoIntermediate, protoPaddedIntermediate} {
		key, iv := make([]byte, 32), make([]byte, 16)
		encoder, _ := newAESCTR(key, iv)
		decoder, _ := newAESCTR(key, iv)
		plain := []byte{0xff, 0xff, 0xff, 0x7f}
		if proto != protoAbridged {
			binary.LittleEndian.PutUint32(plain, 0x7fffffff)
		}
		encoded := make([]byte, len(plain))
		encoder.XORKeyStream(encoded, plain)
		splitter := &MsgSplitter{stream: decoder, protoType: proto}
		parts := splitter.Split(encoded)
		if !splitter.disabled || len(parts) != 1 || !bytes.Equal(parts[0], encoded) || len(splitter.cipherBuf) != 0 {
			t.Fatalf("oversized packet remained buffered for protocol %d", proto)
		}
	}
}

func TestFakeTLSHelloAuthentication(t *testing.T) {
	secret := bytes.Repeat([]byte{1}, 16)
	hello := make([]byte, 100)
	hello[0], hello[5], hello[43] = tlsRecordHandshake, 1, 32
	mac := hmacSHA256(secret, hello)
	copy(hello[11:43], mac)
	ts := make([]byte, 4)
	binary.LittleEndian.PutUint32(ts, uint32(time.Now().Unix()))
	for i := range ts {
		hello[39+i] ^= ts[i]
	}
	if _, _, ok := verifyClientHello(hello, secret); !ok {
		t.Fatal("rejected authenticated hello")
	}
	hello[11] ^= 1
	if _, _, ok := verifyClientHello(hello, secret); ok {
		t.Fatal("accepted tampered hello")
	}
	for n := 0; n < 43; n++ {
		if _, _, ok := verifyClientHello(hello[:n], secret); ok {
			t.Fatalf("accepted truncated hello of %d bytes", n)
		}
	}
}

func TestProxyStopReleasesPortAndIncompleteHandshakes(t *testing.T) {
	// Suppress remote domain-list refreshes; this test uses loopback only.
	cfproxyMu.Lock()
	previousDomain := cfproxyUserDomain
	cfproxyUserDomain = "test.example.co.uk"
	cfproxyMu.Unlock()
	t.Cleanup(func() {
		stopProxy()
		cfproxyMu.Lock()
		cfproxyUserDomain = previousDomain
		cfproxyMu.Unlock()
	})
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	for i := 0; i < 25; i++ {
		if code := startProxy("127.0.0.1", port, "", "00112233445566778899aabbccddeeff", false); code != 0 {
			t.Fatalf("start %d returned %d", i, code)
		}
		client, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), time.Second)
		if err != nil {
			t.Fatal(err)
		}
		done := make(chan int, 1)
		go func() { done <- stopProxy() }()
		select {
		case code := <-done:
			if code != 0 {
				t.Fatalf("stop returned %d", code)
			}
		case <-time.After(2 * time.Second):
			client.Close()
			t.Fatal("stop waited on an incomplete client handshake")
		}
		client.Close()
		if stats.connectionsActive.Load() != 0 {
			t.Fatal("active clients remained after shutdown")
		}
	}
}

func TestProxyRejectsInvalidStartupConfiguration(t *testing.T) {
	for _, secret := range []string{"", "short", strings.Repeat("z", 32), strings.Repeat("0", 32)} {
		if code := startProxy("127.0.0.1", 1443, "", secret, false); code != -2 {
			t.Errorf("invalid secret returned %d", code)
		}
	}
	for _, port := range []int{-1, 0, 65536} {
		if code := startProxy("127.0.0.1", port, "", "00112233445566778899aabbccddeeff", false); code != -2 {
			t.Errorf("port %d returned %d", port, code)
		}
	}
}

func FuzzUpgradeParser(f *testing.F) {
	f.Add(upgradeResponse("test", ""))
	f.Add("HTTP/1.1 429 Too Many Requests\r\n\r\n")
	f.Fuzz(func(t *testing.T, response string) {
		_ = readWebSocketUpgrade(bufio.NewReader(strings.NewReader(response)), "test")
	})
}
