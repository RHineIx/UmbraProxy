package io.github.umbraproxy

import org.json.JSONObject
import java.time.Instant

enum class LogExportFormat(val extension: String) { TEXT("log"), JSON_LINES("jsonl") }

object LogExporter {
    fun export(entries: List<LogEntry>, snapshot: LogSnapshot, version: String, format: LogExportFormat): String {
        val exportedAt = Instant.now().toString()
        if (format == LogExportFormat.TEXT) return buildString {
            appendLine("# UmbraProxy $version | exported=$exportedAt | timestamps=UTC")
            appendLine("# shown=${entries.size} retained=${snapshot.entries.size} evicted=${snapshot.evicted} native_dropped=${snapshot.nativeDropped}")
            snapshot.captureError?.let { appendLine("# capture_error=${LogText.redact(it)}") }
            entries.forEach { appendLine(LogText.line(it)) }
        }
        val metadata = JSONObject().put("type", "metadata").put("version", version).put("exported_at", exportedAt)
            .put("shown", entries.size).put("retained", snapshot.entries.size)
            .put("evicted", snapshot.evicted).put("native_dropped", snapshot.nativeDropped)
            .put("capture_error", snapshot.captureError?.let(LogText::redact) ?: JSONObject.NULL)
        return buildString {
            appendLine(metadata.toString())
            entries.forEach { entry ->
                appendLine(JSONObject().put("type", "event").put("id", entry.id)
                    .put("timestamp", Instant.ofEpochMilli(entry.timestampMillis).toString())
                    .put("timestamp_ms", entry.timestampMillis).put("level", entry.level.name)
                    .put("source", entry.source).put("pid", entry.pid)
                    .put("thread", entry.thread ?: JSONObject.NULL)
                    .put("native_sequence", entry.nativeSequence ?: JSONObject.NULL)
                    .put("message", LogText.redact(entry.message))
                    .put("detail", entry.detail?.let(LogText::redact) ?: JSONObject.NULL)
                    .put("truncated", entry.truncated).toString())
            }
        }
    }
}
