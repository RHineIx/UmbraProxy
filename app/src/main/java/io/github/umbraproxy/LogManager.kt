package io.github.umbraproxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

enum class LogLevel { INFO, WARN, ERROR, DEBUG, UNKNOWN }

data class LogEntry(val level: LogLevel, val message: String)

object LogManager {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var logcatJob: Job? = null
    private var process: Process? = null

    // Prevent memory leaks by limiting the buffer size
    private const val MAX_LOGS = 500

    fun startLogging() {
        if (logcatJob?.isActive == true) return

        logcatJob = scope.launch {
            try {
                // Read logcat output specifically for the UmbraProxy tag
                process = Runtime.getRuntime().exec("logcat -v time -s UmbraProxy:V")
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                
                var line: String?
                while (isActive) {
                    line = reader.readLine()
                    if (line == null) break
                    
                    // Extract the core message ignoring the raw Android logcat prefixes
                    val msgStartIndex = line.indexOf("UmbraProxy")
                    val cleanMessage = if (msgStartIndex != -1) {
                        val colonIndex = line.indexOf(":", msgStartIndex)
                        if (colonIndex != -1) {
                            line.substring(colonIndex + 1).trim()
                        } else {
                            line.substring(msgStartIndex).trim()
                        }
                    } else {
                        line.trim()
                    }

                    // Ignore empty or system beginning lines
                    if (cleanMessage.isBlank() || cleanMessage == "--------- beginning of main" || cleanMessage == "--------- beginning of system") {
                        continue
                    }

                    // Categorize based on the Go core output format
                    val level = when {
                        cleanMessage.contains("[ERROR]") -> LogLevel.ERROR
                        cleanMessage.contains("[WARN]") -> LogLevel.WARN
                        cleanMessage.contains("[DEBUG]") -> LogLevel.DEBUG
                        cleanMessage.contains("[INFO]") -> LogLevel.INFO
                        else -> LogLevel.UNKNOWN
                    }

                    val entry = LogEntry(level, cleanMessage)
                    
                    val currentList = _logs.value.toMutableList()
                    currentList.add(entry)
                    if (currentList.size > MAX_LOGS) {
                        currentList.removeAt(0)
                    }
                    _logs.value = currentList
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopLogging() {
        logcatJob?.cancel()
        process?.destroy()
        logcatJob = null
        process = null
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
