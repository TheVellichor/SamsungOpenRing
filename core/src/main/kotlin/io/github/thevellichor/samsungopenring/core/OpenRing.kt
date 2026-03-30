package io.github.thevellichor.samsungopenring.core

import android.content.Context
import android.util.Log

object OpenRing {

    private const val TAG = "OpenRing"

    private var connection: RingConnection? = null
    var logger: OpenRingLogger? = null

    val isConnected: Boolean get() = connection?.isConnected == true

    fun connect(context: Context, callback: ConnectionCallback) {
        if (connection?.isConnected == true) {
            Log.d(TAG, "Already connected")
            callback.onConnected()
            return
        }

        if (!RingScanner.isBluetoothEnabled(context)) {
            callback.onError(OpenRingError.BluetoothDisabled)
            return
        }

        val device = RingScanner.findBondedRing(context)
        if (device == null) {
            callback.onError(OpenRingError.RingNotFound)
            return
        }

        val conn = RingConnection(context, callback)
        connection = conn
        conn.connect(device)
    }

    fun disconnect() {
        connection?.disconnect()
        connection = null
    }

    fun enableGestures(listener: GestureListener) {
        val conn = connection
        if (conn == null || !conn.isConnected) {
            Log.e(TAG, "Cannot enable gestures: not connected")
            return
        }
        conn.enableGestures(listener)
    }

    fun disableGestures() {
        val conn = connection
        if (conn == null || !conn.isConnected) {
            Log.e(TAG, "Cannot disable gestures: not connected")
            return
        }
        conn.disableGestures()
    }
}
