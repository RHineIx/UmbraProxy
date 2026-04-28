package io.github.umbraproxy

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel { INFO, WARN, ERROR, DEBUG, UNKNOWN }

/**
 * Immutable data class ensures Compose skips recomposition
 * when the reference hasn't changed.
 */
@Immutable
data class LogEntry(
    val key: String,
    val level: LogLevel,
    val message: String,
    val count: Int = 1
)

/**
 * Optimized LogManager: uses a Channel + batching approach to avoid
 * UI jank caused by high-frequency log updates. Merges duplicates.
 */
object LogManager {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var logcatProcess: Process? = null
    private val nextKey = AtomicLong(0)

    // Buffered channel absorbs bursts of log lines
    private val logChannel = Channel<LogEntry>(capacity = Channel.BUFFERED)

    fun startLogging() {
        if (job?.isActive == true) return
        
        job = scope.launch {
            // Start logcat reader coroutine
            val readerJob = launch(Dispatchers.IO) {
                try {
                    val pid = android.os.Process.myPid()
                    val process = ProcessBuilder("logcat", "-v", "tag", "--pid", pid.toString())
                        .redirectErrorStream(true)
                        .start()
                        
                    logcatProcess = process
                    
                    process.inputStream.bufferedReader().use { reader ->
                        while (isActive) {
                            val line = try { reader.readLine() } catch (e: Exception) { null } ?: break
                            val entry = parseLine(line) ?: continue
                            logChannel.trySend(entry)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    logcatProcess?.destroy()
                    logcatProcess = null
                }
            }

            // Batch consumer: collects queued entries and applies in batches
            launch {
                val pendingBatch = mutableListOf<LogEntry>()
                while (isActive) {
                    var received = logChannel.tryReceive()
                    while (received.isSuccess) {
                        pendingBatch.add(received.getOrThrow())
                        if (pendingBatch.size >= 20) break // cap batch size
                        received = logChannel.tryReceive()
                    }

                    if (pendingBatch.isNotEmpty()) {
                        _logs.value = applyBatch(_logs.value, pendingBatch)
                        pendingBatch.clear()
                    }

                    // Throttle updates — 150ms between UI refreshes for extreme smoothness
                    delay(150)
                }
            }

            readerJob.join()
        }
    }

    private fun applyBatch(current: List<LogEntry>, batch: List<LogEntry>): List<LogEntry> {
        val result = ArrayDeque(current)
        for (entry in batch) {
            var merged = false
            // Search last 10 entries to merge consecutive identical messages
            val searchDepth = minOf(result.size, 10)
            for (i in result.indices.reversed().take(searchDepth)) {
                if (result[i].message == entry.message && result[i].level == entry.level) {
                    val existing = result.removeAt(i)
                    result.addLast(existing.copy(count = existing.count + entry.count))
                    merged = true
                    break
                }
            }
            if (!merged) {
                result.addLast(entry)
            }
        }
        // Strict memory limit
        while (result.size > 500) {
            result.removeFirst()
        }
        return result.toList()
    }

    fun stopLogging() {
        job?.cancel()
        job = null
        logcatProcess?.destroy()
        logcatProcess = null
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun parseLine(raw: String): LogEntry? {
        if (!raw.contains("UmbraProxy")) return null

        val messageStr: String
        val level: LogLevel

        when {
            raw.contains("[ERROR]") -> {
                messageStr = raw.substringAfter("[ERROR]").trim()
                level = LogLevel.ERROR
            }
            raw.contains("[WARN]") -> {
                messageStr = raw.substringAfter("[WARN]").trim()
                level = LogLevel.WARN
            }
            raw.contains("[DEBUG]") -> {
                messageStr = raw.substringAfter("[DEBUG]").trim()
                level = LogLevel.DEBUG
            }
            raw.contains("[INFO]") -> {
                messageStr = raw.substringAfter("[INFO]").trim()
                level = LogLevel.INFO
            }
            else -> {
                // Fallback for info lines without tags
                val msgStartIndex = raw.indexOf("UmbraProxy:")
                messageStr = if (msgStartIndex != -1) raw.substringAfter("UmbraProxy:").trim() else raw.trim()
                if (messageStr.isBlank() || messageStr.startsWith("---------")) return null
                level = LogLevel.INFO
            }
        }

        return LogEntry(
            key = "log_${nextKey.getAndIncrement()}",
            level = level,
            message = messageStr,
            count = 1
        )
    }
}
