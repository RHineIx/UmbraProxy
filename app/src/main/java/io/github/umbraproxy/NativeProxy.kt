package io.github.umbraproxy

object NativeProxy {
    
    init {
        System.loadLibrary("umbraproxy")
    }

    external fun startProxy(port: String, cfEnabled: Boolean): Boolean
    
    external fun stopProxy()
    
    external fun getSecretWithPrefix(): String?
}