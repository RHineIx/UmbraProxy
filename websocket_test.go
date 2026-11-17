package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

func TestFragmentedWebSocketWithInterleavedPing(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(2 * time.Second))
	server.SetDeadline(time.Now().Add(2 * time.Second))
	ws := &RawWebSocket{conn: client, bufReader: bufio.NewReader(client)}
	frames := []byte{0x02, 2, 'h', 'e', 0x89, 1, '!', 0x00, 2, 'l', 'l', 0x80, 1, 'o'}
	done := make(chan error, 1)
	go func() {
		if _, err := server.Write(frames[:7]); err != nil {
			done <- err
			return
		}
		// Client pong must be masked and echo the ping payload.
		var pong [7]byte
		_, err := io.ReadFull(server, pong[:])
		if err == nil && (pong[0] != 0x8a || pong[1] != 0x81 || pong[6]^pong[2] != '!') {
			err = io.ErrUnexpectedEOF
		}
		if err == nil {
			_, err = server.Write(frames[7:])
		}
		server.Close()
		done <- err
	}()
	var payload []byte
	for {
		part, err := ws.Recv()
		payload = append(payload, part...)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	if string(payload) != "hello" {
		t.Fatalf("lost fragmented bytes: %q", payload)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestWebSocketRejectsMalformedFraming(t *testing.T) {
	for name, frames := range map[string][]byte{
		"orphan continuation":                {0x80, 0},
		"new data before final continuation": {0x02, 0, 0x82, 0},
		"fragmented ping":                    {0x09, 0},
		"oversized ping":                     {0x89, 126, 0, 126},
		"masked server":                      {0x82, 0x80},
		"extension":                          {0xc2, 0},
		"unknown opcode":                     {0x83, 0},
		"one-byte close":                     {0x88, 1, 0},
	} {
		t.Run(name, func(t *testing.T) {
			ws := &RawWebSocket{bufReader: bufio.NewReader(bytes.NewReader(frames))}
			for {
				_, _, err := ws.readFrame()
				if err == io.EOF {
					t.Fatal("malformed frame was accepted")
				}
				if err != nil {
					break
				}
			}
		})
	}
}

func TestWebSocketFragmentedMessageLimit(t *testing.T) {
	// Exercise the cumulative bound without allocating a 16MB message.
	ws := &RawWebSocket{fragmented: true, fragmentBytes: 16*1024*1024 - 1,
		bufReader: bufio.NewReader(bytes.NewReader([]byte{0x80, 2, 'a', 'b'}))}
	if _, _, err := ws.readFrame(); err == nil {
		t.Fatal("accepted oversized fragmented message")
	}
}

func TestWebSocketLargeBinaryFrame(t *testing.T) {
	payload := bytes.Repeat([]byte{42}, 70000)
	frame := []byte{0x82, 127}
	frame = binary.BigEndian.AppendUint64(frame, uint64(len(payload)))
	frame = append(frame, payload...)
	ws := &RawWebSocket{bufReader: bufio.NewReader(bytes.NewReader(frame))}
	got, err := ws.Recv()
	if err != nil || !bytes.Equal(got, payload) {
		t.Fatalf("large frame: %v", err)
	}
}

func TestSendInitCancellationInterruptsBlockedWriter(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	ws := &RawWebSocket{conn: client}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	started := time.Now()
	if err := sendWSInit(ctx, ws, []byte("blocked")); err == nil {
		t.Fatal("expected cancellation")
	}
	if time.Since(started) > time.Second {
		t.Fatal("initial write exceeded connection deadline")
	}
}
