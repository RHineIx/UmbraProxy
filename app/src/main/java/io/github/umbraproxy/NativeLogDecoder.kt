package io.github.umbraproxy

import org.json.JSONObject

/** The level and timestamp come from the writer, never from words inside a message. */
object NativeLogDecoder {
    data class Batch(val entries: List<LogEntry>, val dropped: Long)

    fun decode(json: String, nextId: () -> Long): Batch {
        val root = JSONObject(json)
        val dropped = root.getLong("dropped")
        require(dropped >= 0)
        val array = root.getJSONArray("entries")
        require(array.length() <= 512) { "Native log batch exceeds protocol limit" }
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val timestamp = item.getLong("timestamp_ms")
            val sequence = item.getLong("sequence")
            val pid = item.getInt("pid")
            require(timestamp > 0 && sequence > 0 && pid > 0) { "Invalid native log metadata" }
            val raw = item.getString("message")
            LogEntry(
                id = nextId(), timestampMillis = timestamp,
                level = LogLevel.valueOf(item.getString("level")), source = item.getString("source").take(64),
                message = LogText.redact(raw).take(4096), pid = pid, nativeSequence = sequence,
                truncated = item.getBoolean("truncated") || raw.length > 4096
            )
        }
        return Batch(entries, dropped)
    }
}
