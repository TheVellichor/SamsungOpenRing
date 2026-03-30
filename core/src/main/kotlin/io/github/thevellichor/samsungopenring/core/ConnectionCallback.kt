package io.github.thevellichor.samsungopenring.core

interface ConnectionCallback {
    fun onConnected()
    fun onDisconnected()
    fun onError(error: OpenRingError)
}
