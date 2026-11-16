package io.github.umbraproxy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoggingTest {
    private fun event(id: Long, time: Long = id, message: String = "message", level: LogLevel = LogLevel.INFO) =
        LogEntry(id, time, level, "Service", message, pid = 42, thread = "main")

    @Test fun repeatsRemainSeparateAndDelayedNativeEventsAreOrderedByEventTime() {
        val store = LogStore(5)
        store.append(listOf(event(1, 300), event(2, 400)))
        store.append(listOf(event(3, 100), event(4, 200)))
        assertEquals(listOf(3L, 4L, 1L, 2L), store.snapshot().entries.map { it.id })
        assertEquals(4, store.snapshot().entries.size)
    }

    @Test fun retentionAndNativeLossAreCountedAndClearResetsHistory() {
        val store = LogStore(2)
        store.append(listOf(event(1), event(2), event(3)), dropped = 7)
        val snapshot = store.snapshot()
        assertEquals(listOf(2L, 3L), snapshot.entries.map { it.id })
        assertEquals(1L, snapshot.evicted)
        assertEquals(7L, snapshot.nativeDropped)
        store.clear()
        store.append(listOf(event(4)))
        assertEquals(2, snapshot.entries.size) // previous snapshots remain immutable
        assertEquals(listOf(4L), store.snapshot().entries.map { it.id })
        assertEquals(0L, store.snapshot().evicted)
        assertEquals(0L, store.snapshot().nativeDropped)
    }

    @Test fun nativeMetadataIsPreservedWithoutInferringSeverityFromMessage() {
        var id = 100L
        val batch = NativeLogDecoder.decode("""{"dropped":2,"entries":[{"sequence":7,"timestamp_ms":1700000000123,"level":"WARN","source":"Core","message":"contains [ERROR] as text","pid":123,"truncated":false}]}""") { ++id }
        val entry = batch.entries.single()
        assertEquals(101L, entry.id)
        assertEquals(7L, entry.nativeSequence)
        assertEquals(1700000000123, entry.timestampMillis)
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals("Core", entry.source)
        assertEquals(123, entry.pid)
        assertEquals(2L, batch.dropped)
    }

    @Test fun nativeBatchEmptyIsValid() {
        val batch = NativeLogDecoder.decode("""{"entries":[],"dropped":0}""") { 1 }
        assertTrue(batch.entries.isEmpty())
        assertEquals(0L, batch.dropped)
    }

    @Test fun malformedNativeEventsAreNotPresentedAsValidLogs() {
        for (json in listOf(
            "not JSON",
            """{"dropped":-1,"entries":[]}""",
            """{"dropped":0,"entries":[{"sequence":1,"timestamp_ms":0,"level":"INFO","source":"Core","message":"x","pid":1,"truncated":false}]}""",
            """{"dropped":0,"entries":[{"sequence":1,"timestamp_ms":1,"level":"MADE_UP","source":"Core","message":"x","pid":1,"truncated":false}]}"""
        )) {
            assertTrue(json, runCatching { NativeLogDecoder.decode(json) { 1 } }.isFailure)
        }
    }

    @Test fun searchIncludesSourceLevelAndException() {
        val entry = event(1, message = "Connection ended", level = LogLevel.WARN).copy(detail = "java.net.SocketException: reset")
        for (query in listOf("", " service ", "warn", "connection", "socketexception")) assertTrue(query, entry.matches(query))
        assertFalse(entry.matches("not present"))
    }

    @Test fun labeledCredentialsAreRedactedInMessagesAndExceptions() {
        for (text in listOf("secret=private-key&port=1443", "PASSWORD: private-key", "authorization: Bearer private-key", "token=private-key")) {
            val clean = LogText.redact(text)
            assertFalse(clean.contains("private-key"))
            assertTrue(clean.contains("[REDACTED]"))
        }
    }

    @Test fun textExportIncludesUtcMetadataAndFullExceptionWithOriginalTimestamp() {
        val entry = event(1, 1700000000123, "secret=private-key").copy(detail = "Exception\n at Socket.read\n password: private-key")
        val snapshot = LogSnapshot(listOf(entry), evicted = 3, nativeDropped = 2)
        val text = LogExporter.export(listOf(entry), snapshot, "1.4.2", LogExportFormat.TEXT)
        assertTrue(text.contains("2023-11-14T22:13:20.123Z"))
        assertTrue(text.contains("[Service] pid=42 thread=main"))
        assertTrue(text.contains("evicted=3 native_dropped=2"))
        assertTrue(text.contains("at Socket.read"))
        assertFalse(text.contains("private-key"))
    }

    @Test fun jsonLinesExportKeepsOneRecordPerEventWithMultilineMessage() {
        val entry = event(1, 1700000000123, "first\nsecond").copy(nativeSequence = 9, truncated = true)
        val lines = LogExporter.export(listOf(entry), LogSnapshot(listOf(entry)), "test", LogExportFormat.JSON_LINES).trimEnd().lines()
        assertEquals(2, lines.size)
        assertEquals("metadata", JSONObject(lines[0]).getString("type"))
        val json = JSONObject(lines[1])
        assertEquals("event", json.getString("type"))
        assertEquals("first\nsecond", json.getString("message"))
        assertEquals(9L, json.getLong("native_sequence"))
        assertTrue(json.getBoolean("truncated"))
    }
}
