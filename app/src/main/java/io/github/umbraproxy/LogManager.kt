package io.github.umbraproxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Bounded, process-local history. UI and service hold independent capture leases. */
object LogManager {
    private val stateLock = Any()
    private val ownerLock = Any()
    private val owners = mutableSetOf<String>()
    private val store = LogStore()
    private val nextId = AtomicLong(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nativeReadMutex = Mutex()
    private var job: Job? = null
    private var captureError: String? = null
    private val _snapshot = MutableStateFlow(LogSnapshot())
    val snapshot = _snapshot.asStateFlow()
    private val _captureDebug = MutableStateFlow(false)
    val captureDebug = _captureDebug.asStateFlow()

    fun startLogging(owner: String = "ui") = synchronized(ownerLock) {
        owners.add(owner)
        if (job?.isActive == true) return@synchronized
        job = scope.launch {
            while (isActive) {
                nativeReadMutex.withLock {
                    try {
                        val batch = NativeLogDecoder.decode(NativeProxy.drainLogs().getOrThrow()) { nextId.incrementAndGet() }
                        synchronized(stateLock) {
                            captureError = null
                            store.append(batch.entries, batch.dropped)
                            _snapshot.value = store.snapshot()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val error = LogText.redact("${e.javaClass.simpleName}: ${e.message.orEmpty()}").take(512)
                        val changed = synchronized(stateLock) {
                            val changed = captureError != error
                            captureError = error
                            _snapshot.value = store.snapshot(captureError)
                            changed
                        }
                        if (changed) record(LogLevel.ERROR, "Logger", "Native log stream unavailable", e)
                    }
                }
                delay(500)
            }
        }
    }

    fun stopLogging(owner: String = "ui") = synchronized(ownerLock) {
        owners.remove(owner)
        if (owners.isEmpty()) { job?.cancel(); job = null }
    }

    fun record(level: LogLevel, source: String, message: String, error: Throwable? = null) {
        if (level == LogLevel.DEBUG && !_captureDebug.value) return
        val clean = LogText.redact(message)
        val detail = error?.stackTraceToString()?.let(LogText::redact)
        val entry = LogEntry(
            id = nextId.incrementAndGet(), timestampMillis = System.currentTimeMillis(), level = level,
            source = source.take(64), message = clean.take(4096), detail = detail?.take(16384),
            pid = android.os.Process.myPid(), thread = Thread.currentThread().name,
            truncated = clean.length > 4096 || (detail?.length ?: 0) > 16384
        )
        synchronized(stateLock) {
            store.append(listOf(entry))
            _snapshot.value = store.snapshot(captureError)
        }
        val priority = when (level) {
            LogLevel.DEBUG -> android.util.Log.DEBUG
            LogLevel.INFO -> android.util.Log.INFO
            LogLevel.WARN -> android.util.Log.WARN
            LogLevel.ERROR -> android.util.Log.ERROR
        }
        android.util.Log.println(priority, "UmbraProxy/$source", entry.message + (entry.detail?.let { "\n$it" } ?: ""))
    }

    suspend fun setDebugCapture(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        nativeReadMutex.withLock {
            NativeProxy.setVerboseLogging(enabled).onSuccess {
                _captureDebug.value = enabled
                record(LogLevel.INFO, "Logger", "Debug capture ${if (enabled) "enabled" else "disabled"}")
            }
        }
    }

    suspend fun clearLogs(): Result<Unit> = withContext(Dispatchers.IO) {
        nativeReadMutex.withLock {
            NativeProxy.clearLogs().onSuccess {
                synchronized(stateLock) { store.clear(); _snapshot.value = store.snapshot(captureError) }
            }
        }
    }
}
