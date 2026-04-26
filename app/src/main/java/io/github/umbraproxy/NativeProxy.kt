package io.github.umbraproxy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface ProxyLibrary : Library {
    companion object {
        // Load the library using JNA (Java Native Access)
        val INSTANCE: ProxyLibrary = Native.load("umbraproxy", ProxyLibrary::class.java) as ProxyLibrary
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetFakeTls(enabled: Int, domain: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeProxy {
    fun startProxy(port: Int, cfEnabled: Boolean, secret: String = ""): Boolean {
        return try {
            // Apply Cloudflare settings
            ProxyLibrary.INSTANCE.SetCfProxyConfig(if (cfEnabled) 1 else 0, 1, "")
            
            // Start the proxy with standard parameters. Returning 0 means success.
            val result = ProxyLibrary.INSTANCE.StartProxy("127.0.0.1", port, "", secret, 0)
            result == 0
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    fun stopProxy() {
        try {
            ProxyLibrary.INSTANCE.StopProxy()
        } catch (e: Throwable) {
            e.printStackTrace()
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
