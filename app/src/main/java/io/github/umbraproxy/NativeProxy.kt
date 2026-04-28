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
    fun SetFakeTls(enabled: Int, domain: String)
    fun SetSecret(secret: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeProxy {

    fun updateConfigForSecret(secret: String, fakeTlsEnabled: Boolean, fakeTlsDomain: String) {
        runCatching {
            // Update the core state so GetSecretWithPrefix() returns the correct URL payload
            ProxyLibrary.INSTANCE.SetSecret(secret)
            ProxyLibrary.INSTANCE.SetFakeTls(if (fakeTlsEnabled) 1 else 0, fakeTlsDomain)
        }
    }

    fun startProxy(port: Int, cfEnabled: Boolean, cfDomain: String, fakeTlsEnabled: Boolean, fakeTlsDomain: String, secret: String, dcIps: String): Boolean {
        return try {
            ProxyLibrary.INSTANCE.SetCfProxyConfig(if (cfEnabled) 1 else 0, 1, cfDomain)
            ProxyLibrary.INSTANCE.SetFakeTls(if (fakeTlsEnabled) 1 else 0, fakeTlsDomain)
            val result = ProxyLibrary.INSTANCE.StartProxy("127.0.0.1", port, dcIps, secret, 0)
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
