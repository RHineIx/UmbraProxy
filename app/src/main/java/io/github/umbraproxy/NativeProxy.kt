package io.github.umbraproxy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface ProxyLibrary : Library {
    companion object {
        // التحميل يتم باسم umbraproxy وسيبحث النظام تلقائياً عن libumbraproxy.so
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
    fun startProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int {
        return try {
            ProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, secret, verbose)
        } catch (e: Throwable) {
            -99 // كود خطأ مخصص لفشل الارتباط بالمكتبة
        }
    }

    fun stopProxy(): Int {
        return ProxyLibrary.INSTANCE.StopProxy()
    }

    fun setPoolSize(size: Int) {
        ProxyLibrary.INSTANCE.SetPoolSize(size)
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        ProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir)
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) {
        ProxyLibrary.INSTANCE.SetCfProxyConfig(
            if (enabled) 1 else 0,
            if (priority) 1 else 0,
            userDomain
        )
    }

    fun setFakeTls(enabled: Boolean, domain: String = "") {
        ProxyLibrary.INSTANCE.SetFakeTls(if (enabled) 1 else 0, domain)
    }

    fun getSecretWithPrefix(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }

    fun getStats(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetStats() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }
}