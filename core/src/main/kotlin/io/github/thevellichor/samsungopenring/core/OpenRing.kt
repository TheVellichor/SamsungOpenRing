package io.github.thevellichor.samsungopenring.core

import android.content.Context
import android.util.Log

object OpenRing {

    private const val TAG = "OpenRing"

    private var connection: RingConnection? = null
    var logger: OpenRingLogger? = null

    /**
     * Optional tap that receives EVERY raw RX notification as (charShortUuid, bytes).
     * Set by the debug bridge to stream the full BLE traffic. Read directly by
     * RingConnection so it survives across (re)connections.
     */
    var rawListener: ((charShort: String, value: ByteArray) -> Unit)? = null

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

    /** Raw passthrough write to the ring's TX characteristic (debug bridge). */
    fun writeRaw(data: ByteArray, withResponse: Boolean = false) {
        connection?.writeRaw(data, withResponse)
    }

    /** Request a BLE connection priority (BluetoothGatt.CONNECTION_PRIORITY_*). */
    fun requestConnectionPriority(priority: Int): Boolean =
        connection?.requestConnectionPriority(priority) ?: false

    /** Dump discovered GATT services/characteristics, or "not connected". */
    fun gattDump(): String = connection?.gattDump() ?: "not connected"

    /** Initiate a safe GATT read of a characteristic by UUID prefix (debug bridge). */
    fun readCharacteristic(uuidPrefix: String): Boolean = connection?.readCharacteristic(uuidPrefix) ?: false

    /** Latest value read for a characteristic UUID prefix, or null (debug bridge). */
    fun getRead(uuidPrefix: String): ByteArray? = connection?.getRead(uuidPrefix)
}
