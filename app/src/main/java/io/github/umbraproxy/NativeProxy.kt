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
    fun FreeString(p: Pointer)
}

object NativeProxy {

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
            val result = ProxyLibrary.INSTANCE.StartProxy("127.0.0.1", port, dcIps, secret, 0)
            result == 0
        } catch (e: Throwable) {
            android.util.Log.e("UmbraProxy", "Native proxy startup failed", e)
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
    }

    fun getStats(): String? = readNativeString { it.GetStats() }

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
