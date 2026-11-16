package io.github.umbraproxy

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

@Immutable
data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val source: String,
    val message: String,
    val detail: String? = null,
    val pid: Int,
    val thread: String? = null,
    val nativeSequence: Long? = null,
    val truncated: Boolean = false
) {
    fun matches(query: String): Boolean = query.isBlank() ||
        listOf(source, level.name, message, detail.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) }
}

@Immutable
data class LogSnapshot(
    val entries: List<LogEntry> = emptyList(),
    val evicted: Long = 0,
    val nativeDropped: Long = 0,
    val captureError: String? = null
)

/** Caller serializes mutations; snapshots never expose a mutable backing list. */
class LogStore(private val capacity: Int = 1000) {
    init { require(capacity > 0) }
    private val entries = mutableListOf<LogEntry>()
    private var evicted = 0L
    private var nativeDropped = 0L

    fun append(batch: List<LogEntry>, dropped: Long = 0) {
        require(dropped >= 0)
        nativeDropped += dropped
        entries.addAll(batch)
        // Native batches can arrive after Kotlin events with later timestamps.
        entries.sortWith(compareBy<LogEntry> { it.timestampMillis }.thenBy { it.id })
        val overflow = (entries.size - capacity).coerceAtLeast(0)
        if (overflow > 0) {
            entries.subList(0, overflow).clear()
            evicted += overflow
        }
    }

    fun snapshot(captureError: String? = null) = LogSnapshot(entries.toList(), evicted, nativeDropped, captureError)

    fun clear() { entries.clear(); evicted = 0; nativeDropped = 0 }
}

object LogText {
    private val credential = Regex("(?i)((?:secret|secret_key|password|token|authorization)\\s*[:=]\\s*)[^\\s&\"']+")
    private val authorization = Regex("(?i)(authorization\\s*[:=]\\s*)(?:Bearer|Basic)\\s+[^\\s&\"']+")
    private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT)
    fun redact(text: String): String {
        val clean = authorization.replace(text) { "${it.groupValues[1]}[REDACTED]" }
        return credential.replace(clean) { "${it.groupValues[1]}[REDACTED]" }
    }
    fun time(timestamp: Long): String = clock.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun line(entry: LogEntry): String = buildString {
        append(Instant.ofEpochMilli(entry.timestampMillis)).append(" ").append(entry.level.name.padEnd(5))
        append(" [").append(entry.source).append("] pid=").append(entry.pid)
        entry.thread?.let { append(" thread=").append(it) }
        append(" ").append(redact(entry.message))
        if (entry.truncated) append(" [TRUNCATED]")
        entry.detail?.let { append("\n").append(redact(it)) }
    }
}
