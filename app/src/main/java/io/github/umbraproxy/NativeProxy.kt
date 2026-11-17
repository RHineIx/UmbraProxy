package io.github.umbraproxy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA Interface for the core Go library.
 * Provides standard C-style bindings to the umbraproxy shared object.
 */
interface ProxyLibrary : Library {
    companion object {
        val INSTANCE: ProxyLibrary = Native.load("umbraproxy", ProxyLibrary::class.java) as ProxyLibrary
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetFakeTls(enabled: Int, domain: String)
    fun SetSecret(secret: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun GetHealth(): Pointer?
    fun ProbeHealth(): Pointer?
    fun FreeString(p: Pointer)
    fun DrainLogs(): Pointer?
    fun ClearLogs()
    fun SetVerboseLogging(enabled: Int)
}

object NativeProxy {
    private var verboseLogging = false

    fun updateConfigForSecret(secret: String, fakeTlsEnabled: Boolean, fakeTlsDomain: String) {
        runCatching {
            ProxyLibrary.INSTANCE.SetSecret(secret)
            ProxyLibrary.INSTANCE.SetFakeTls(if (fakeTlsEnabled) 1 else 0, fakeTlsDomain)
        }
    }

    @Synchronized
    fun startProxy(port: Int, cfEnabled: Boolean, cfDomain: String, fakeTlsEnabled: Boolean, fakeTlsDomain: String, secret: String, dcIps: String, poolSize: Int = 4, cacheDir: String = "", shouldStart: () -> Boolean = { true }): Boolean {
        // Check inside the lifecycle lock: destruction may precede a queued IO call.
        if (!shouldStart()) return false
        return try {
            ProxyLibrary.INSTANCE.SetPoolSize(poolSize)
            ProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir)
            ProxyLibrary.INSTANCE.SetCfProxyConfig(if (cfEnabled) 1 else 0, 1, cfDomain)
            ProxyLibrary.INSTANCE.SetFakeTls(if (fakeTlsEnabled) 1 else 0, fakeTlsDomain)
            val result = ProxyLibrary.INSTANCE.StartProxy("127.0.0.1", port, dcIps, secret, if (verboseLogging) 1 else 0)
            if (result != 0) AppLog.e("Native", "Proxy startup rejected (code=$result)")
            result == 0
        } catch (e: Throwable) {
            AppLog.e("Native", "Native proxy startup failed", e)
            false
        }
    }

    @Synchronized
    fun restartProxy(port: Int, cfEnabled: Boolean, cfDomain: String, fakeTlsEnabled: Boolean, fakeTlsDomain: String, secret: String, dcIps: String, poolSize: Int, cacheDir: String, shouldStart: () -> Boolean): Boolean {
        stopProxy()
        return startProxy(port, cfEnabled, cfDomain, fakeTlsEnabled, fakeTlsDomain, secret, dcIps, poolSize, cacheDir, shouldStart)
    }

    @Synchronized
    fun stopProxy() {
        runCatching { ProxyLibrary.INSTANCE.StopProxy() }
            .onFailure { AppLog.e("Native", "Native proxy shutdown failed", it) }
    }

    fun drainLogs(): Result<String> = runCatching {
        val library = ProxyLibrary.INSTANCE
        val pointer = library.DrainLogs() ?: error("Native log stream returned a null pointer")
        try { pointer.getString(0, "UTF-8") } finally { library.FreeString(pointer) }
    }

    fun clearLogs(): Result<Unit> = runCatching { ProxyLibrary.INSTANCE.ClearLogs() }

    @Synchronized
    fun setVerboseLogging(enabled: Boolean): Result<Unit> = runCatching {
        ProxyLibrary.INSTANCE.SetVerboseLogging(if (enabled) 1 else 0)
        verboseLogging = enabled
    }

    fun getStats(): String? = readNativeString { it.GetStats() }

    fun getHealth(): ProxyHealth? = readNativeString { it.GetHealth() }?.let(ProxyHealth::decode)

    // Do not take the lifecycle monitor while probing: StopProxy cancels the Go context.
    fun probeHealth(): ProxyHealth? = readNativeString { it.ProbeHealth() }?.let(ProxyHealth::decode)

    fun getSecretWithPrefix(): String? = readNativeString { it.GetSecretWithPrefix() }

    private fun readNativeString(read: (ProxyLibrary) -> Pointer?): String? = runCatching {
        val library = ProxyLibrary.INSTANCE
        val pointer = read(library) ?: return@runCatching null
        try {
            pointer.getString(0, "UTF-8")
        } finally {
            library.FreeString(pointer)
        }
    }.getOrNull()
}
