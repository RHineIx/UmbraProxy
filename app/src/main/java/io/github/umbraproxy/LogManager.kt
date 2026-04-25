package io.github.umbraproxy

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

@Immutable
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int,
    val isError: Boolean,
    val priority: Int, // 3=DEBUG, 4=INFO, 5=WARN, 6=ERROR
    val isEssential: Boolean = false
)

object LogManager {
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    private var job: Job? = null
    private var logcatProcess: Process? = null
    private val nextKey = AtomicLong(0)

    private val logChannel = Channel<LogEntry>(capacity = Channel.BUFFERED)

    fun startListening() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO).launch {
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
                    Log.e("LogManager", "Failed to read logcat", e)
                } finally {
                    logcatProcess?.destroy()
                    logcatProcess = null
                }
            }

            launch {
                val pendingBatch = mutableListOf<LogEntry>()
                while (isActive) {
                    var received = logChannel.tryReceive()
                    while (received.isSuccess) {
                        pendingBatch.add(received.getOrThrow())
                        if (pendingBatch.size >= 20) break
                        received = logChannel.tryReceive()
                    }

                    if (pendingBatch.isNotEmpty()) {
                        logs.value = applyBatch(logs.value, pendingBatch)
                        pendingBatch.clear()
                    }
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
            val searchDepth = minOf(result.size, 10)
            for (i in result.indices.reversed().take(searchDepth)) {
                if (result[i].message == entry.message) {
                    val existing = result.removeAt(i)
                    result.addLast(existing.copy(count = existing.count + 1))
                    merged = true
                    break
                }
            }
            if (!merged) {
                result.addLast(entry)
            }
        }
        while (result.size > 50) {
            result.removeFirst()
        }
        return result.toList()
    }

    fun stopListening() {
        job?.cancel()
        job = null
        logcatProcess?.destroy()
        logcatProcess = null
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    private fun parseLine(raw: String): LogEntry? {
        var message: String
        val isError: Boolean
        val priority: Int

        // Now looking for UmbraProxy instead of TgWsProxy
        when {
            raw.contains("[ERROR]") -> {
                message = raw.substringAfter("[ERROR]").trim()
                isError = true
                priority = 6
            }
            raw.contains("[WARN]") -> {
                message = raw.substringAfter("[WARN]").trim()
                isError = false
                priority = 5
            }
            raw.contains("[DEBUG]") -> {
                message = raw.substringAfter("[DEBUG]").trim()
                isError = false
                priority = 3
            }
            raw.contains("UmbraProxy") -> {
                var msg = raw.substringAfter("UmbraProxy:").trim()
                if (msg.startsWith("[ERROR]") || msg.startsWith("[WARN]") || msg.startsWith("[DEBUG]")) {
                    return null
                }

                if (msg.contains("↑")) {
                    msg = msg.substringBefore("↑").trim()
                }
                if (msg.contains("↓")) {
                    msg = msg.substringBefore("↓").trim()
                }

                message = msg
                isError = false
                priority = 4
            }
            else -> return null
        }

        val emojiRegex = Regex("[\\x{1F300}-\\x{1F5FF}\\x{1F900}-\\x{1F9FF}\\x{1F600}-\\x{1F64F}\\x{1F680}-\\x{1F6FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{1F191}-\\x{1F251}\\x{1F004}\\x{1F0CF}\\x{1F170}-\\x{1F171}\\x{1F17E}-\\x{1F17F}\\x{1F18E}\\x{3030}\\x{2B50}\\x{2B55}\\x{2934}-\\x{2935}\\x{2B05}-\\x{2B07}\\x{2B1B}-\\x{2B1C}\\x{3297}\\x{3299}\\x{303D}\\x{00A9}\\x{00AE}\\x{2122}\\x{23F3}\\x{24C2}\\x{23E9}-\\x{23EF}\\x{25B6}\\x{23F8}-\\x{23FA}⚠✅❌⚡🔥🔄🔗]")
        message = message.replace(emojiRegex, "").trim()

        val isEssential = message.contains("Пул", ignoreCase = true) ||
                message.contains("Ключ:", ignoreCase = true) ||
                message.contains("запущен", ignoreCase = true) ||
                message.contains("Адрес:", ignoreCase = true) ||
                message.contains("ошибка", ignoreCase = true) ||
                message.contains("провалены", ignoreCase = true) ||
                message.contains("заблокирован", ignoreCase = true)

        return LogEntry(
            key = "log_${nextKey.getAndIncrement()}",
            message = message,
            count = 1,
            isError = isError,
            priority = priority,
            isEssential = isEssential
        )
    }
}