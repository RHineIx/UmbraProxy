# Proxy log viewer

The viewer records actual application events directly, rather than scraping
`logcat` text. It does not generate demonstration entries or merge repeated
messages. It covers UmbraProxy's instrumented Kotlin service/controller/native
bridge and Go core; it is not a viewer for every Android system event.

Each event carries its original timestamp, an explicit DEBUG/INFO/WARN/ERROR
level, a source, PID and message. Kotlin events include the calling thread and
exception trace when available; core events include a monotonic native sequence.
A word such as `[ERROR]` inside an INFO message cannot change its level.

The console provides separate persistent level filters, case-insensitive search
across source/message/exception/level, pause/resume **display**, a follow-latest
toggle, text selection, copy, clear, and exports through Android's file picker.
Dragging the list disables following so new events do not pull it away while
reading. Pausing the display keeps capture running.

Release builds retain source-file and line-number metadata for exception traces.
The release workflow preserves the R8 mapping as a separate Actions artifact
(`umbra-mapping-<tag>`) for deobfuscating optimized frames; it is not attached to
the public APK release assets.

Core DEBUG capture is opt-in and lasts for the current app process. It enables
extra actual connection/handshake diagnostics and survives restarting the core
within that process. The DEBUG filter controls visibility independently.

## Retention and export

History retains the last 1,000 events **in process memory**. It survives closing
the log screen while the foreground service is active, but not Android killing
the app process. Kotlin and Go events are ordered by their recorded times.

Go has a separate 512-event queue which is drained through JNA every 500 ms while
the UI or service holds a capture lease. Queue overwrites and history evictions
are counted and displayed explicitly. There is no claim of lossless capture
under unlimited load. Messages are limited to 4,096 bytes in Go / 4,096 characters
in Kotlin; exception traces to 16,384 characters. Clipping is explicitly marked.

Exports contain the filtered snapshot selected at export time, UTC timestamps,
application version, retention/loss counters, and complete retained exception
traces. JSONL includes a metadata object followed by one JSON object per event;
newlines inside a message remain escaped within that event. The console uses
local device time. Clipboard copy refuses payloads above 256 KiB; export to a
file for larger histories.

Labeled secrets, passwords, tokens and authorization values are redacted before
storage and again during export. Network messages and exception traces can still
contain diagnostic addresses/paths; exports are saved only when requested.

## Verification

- Go tests cover queue overflow, duplicate preservation, concurrent writers and
  drains, clear, Unicode clipping/redaction, and original writer metadata.
- JVM tests cover event order, immutable snapshots, retention/loss accounting,
  searching, strict native decoding and text/JSONL exports with real timestamps.
- A C ABI smoke test starts a loopback proxy, sends an invalid handshake, stops
  it, and verifies the resulting INFO/DEBUG/WARN events through DrainLogs and
  FreeString, including timestamps, PID, sequences, drain and clear behavior.
- Rebuild both native libraries before building the APK: the JNA interface now
  requires DrainLogs, ClearLogs and SetVerboseLogging.

Device checks: open/close the screen during an active connection; background and
resume the app; pause while generating traffic; drag away from the bottom; filter
WARN independently; export both formats; clear while paused; switch themes and
Arabic/English; check a narrow screen and a large accessibility font.
