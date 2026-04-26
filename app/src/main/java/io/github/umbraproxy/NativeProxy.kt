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
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeProxy {

    fun startProxy(port: Int, cfEnabled: Boolean, secret: String): Boolean {
        return try {
            ProxyLibrary.INSTANCE.SetCfProxyConfig(if (cfEnabled) 1 else 0, 1, "")
            val result = ProxyLibrary.INSTANCE.StartProxy("127.0.0.1", port, "", secret, 0)
            result == 0
        } catch (e: Throwable) {
            false
        }
    }

    fun stopProxy() {
        runCatching { ProxyLibrary.INSTANCE.StopProxy() }
    }

    fun getStats(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetStats() ?: return null
            val stats = ptr.getString(0)
            ProxyLibrary.INSTANCE.FreeString(ptr)
            stats
        } catch (e: Throwable) {
            null
        }
    }

    fun getSecretWithPrefix(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
            val res = ptr.getString(0)
            ProxyLibrary.INSTANCE.FreeString(ptr)
            res
        } catch (e: Throwable) {
            null
        }
    }
}
