package io.github.umbraproxy

/** Records remain available in release builds, independently of R8 logcat rules. */
object AppLog {
    fun d(source: String, message: String, error: Throwable? = null) = LogManager.record(LogLevel.DEBUG, source, message, error)
    fun i(source: String, message: String, error: Throwable? = null) = LogManager.record(LogLevel.INFO, source, message, error)
    fun w(source: String, message: String, error: Throwable? = null) = LogManager.record(LogLevel.WARN, source, message, error)
    fun e(source: String, message: String, error: Throwable? = null) = LogManager.record(LogLevel.ERROR, source, message, error)
}
