package io.github.thevellichor.samsungopenring.core

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

internal class RingConnection(
    private val context: Context,
    private val connectionCallback: ConnectionCallback,
) {
    companion object {
        private const val TAG = "OpenRing.Connection"
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var gestureListener: GestureListener? = null
    @Volatile private var closing = false
    @Volatile private var gesturesDesired = false  // true = we want gestures on
    @Volatile private var disableRequested = false // true = WE sent the disable
    private var device: BluetoothDevice? = null
    private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingSubscriptions = mutableListOf<BluetoothGattCharacteristic>()
    private val readResults = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    val isConnected: Boolean get() = gatt != null && txCharacteristic != null

    fun connect(device: BluetoothDevice) {
        this.device = device
        this.closing = false
        this.reconnectAttempts = 0
        emit("BLE connecting to ${device.name} (${device.address})")
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            emit("BLE connect FAILED: ${e.message}")
            mainHandler.post { connectionCallback.onError(OpenRingError.PermissionDenied) }
        }
    }

    fun disconnect() {
        closing = true
        emit("BLE disconnecting (user-initiated)")
        mainHandler.removeCallbacksAndMessages(null)
        val g = gatt
        if (g != null) {
            try {
                g.disconnect()
                // close() will be called in onConnectionStateChange -> STATE_DISCONNECTED
            } catch (e: SecurityException) {
                emit("BLE disconnect error: ${e.message}")
                cleanupGatt(g)
            }
        } else {
            gatt = null
            txCharacteristic = null
        }
    }

    fun enableGestures(listener: GestureListener) {
        gestureListener = listener
        gesturesDesired = true
        disableRequested = false
        writeCommand(RingProtocol.CMD_ENABLE_GESTURES, "ENABLE_GESTURE")
    }

    fun disableGestures() {
        gesturesDesired = false
        disableRequested = true
        writeCommand(RingProtocol.CMD_DISABLE_GESTURES, "DISABLE_GESTURE")
        gestureListener = null
    }

    /** Write arbitrary bytes to the TX characteristic. Raw passthrough used by the debug bridge. */
    fun writeRaw(data: ByteArray, withResponse: Boolean = false) {
        writeCommand(data, "RAW", withResponse)
    }

    /**
     * Request a BLE connection priority for this client.
     * Use BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER (2) to relax the connection
     * interval and reduce radio drain. Returns false if not connected.
     */
    fun requestConnectionPriority(priority: Int): Boolean {
        val g = gatt ?: return false
        return try {
            val ok = g.requestConnectionPriority(priority)
            emit("requestConnectionPriority($priority) -> $ok")
            ok
        } catch (e: SecurityException) {
            emit("requestConnectionPriority error: ${e.message}")
            false
        }
    }

    /** Initiate a GATT read of the first characteristic whose UUID starts with [uuidPrefix]. Safe (read-only). */
    fun readCharacteristic(uuidPrefix: String): Boolean {
        val g = gatt ?: return false
        val target = g.services.flatMap { it.characteristics }
            .firstOrNull { it.uuid.toString().startsWith(uuidPrefix.lowercase()) } ?: return false
        readResults.remove(target.uuid.toString())
        return try { g.readCharacteristic(target) } catch (e: SecurityException) { emit("read err: ${e.message}"); false }
    }

    /** Latest value read for a characteristic whose UUID starts with [uuidPrefix], or null. */
    fun getRead(uuidPrefix: String): ByteArray? =
        readResults.entries.firstOrNull { it.key.startsWith(uuidPrefix.lowercase()) }?.value

    /** Human-readable dump of discovered GATT services/characteristics (debug bridge). */
    fun gattDump(): String {
        val g = gatt ?: return "not connected"
        val sb = StringBuilder()
        for (svc in g.services) {
            sb.append("SVC ${svc.uuid}\n")
            for (c in svc.characteristics) {
                sb.append("  CHAR ${c.uuid} props=0x${Integer.toHexString(c.properties)} inst=${c.instanceId}\n")
            }
        }
        return sb.toString()
    }

    private fun writeCommand(data: ByteArray, label: String, withResponse: Boolean = false) {
        val tx = txCharacteristic
        val g = gatt
        if (tx == null || g == null) {
            emit("WRITE FAILED ($label): not connected")
            return
        }

        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val hex = data.joinToString(" ") { "%02x".format(it) }
        emit("WRITE -> $label [$hex] on ${tx.uuid.toString().substring(0, 8)}")

        try {
            tx.writeType = type
            g.writeCharacteristic(tx, data, type)
        } catch (e: SecurityException) {
            emit("WRITE SecurityException: ${e.message}")
        }
    }

    private fun cleanupGatt(g: BluetoothGatt) {
        try {
            g.close()
        } catch (_: Exception) {}
        gatt = null
        txCharacteristic = null
    }

    private fun attemptReconnect() {
        if (closing) return
        val dev = device ?: return

        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            emit("Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            mainHandler.post { connectionCallback.onError(OpenRingError.NotConnected) }
            return
        }

        val delay = RECONNECT_DELAY_MS * reconnectAttempts.coerceAtMost(3)
        emit("Reconnecting in ${delay / 1000}s (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")

        mainHandler.postDelayed({
            if (closing) return@postDelayed
            emit("Reconnecting to ${dev.name}...")
            try {
                gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                emit("Reconnect FAILED: ${e.message}")
                attemptReconnect()
            }
        }, delay)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val stateStr = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                else -> "UNKNOWN($newState)"
            }
            emit("BLE state: $stateStr (gatt_status=$status)")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        emit("Connection reported CONNECTED but status=$status, treating as failure")
                        cleanupGatt(g)
                        attemptReconnect()
                        return
                    }
                    reconnectAttempts = 0
                    emit("Starting GATT service discovery...")
                    try {
                        g.discoverServices()
                    } catch (e: SecurityException) {
                        emit("discoverServices failed: ${e.message}")
                        cleanupGatt(g)
                        attemptReconnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    cleanupGatt(g)
                    if (closing) {
                        emit("Clean disconnect complete")
                        mainHandler.post { connectionCallback.onDisconnected() }
                    } else {
                        emit("Unexpected disconnect")
                        mainHandler.post { connectionCallback.onDisconnected() }
                        attemptReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit("Service discovery FAILED (status=$status)")
                cleanupGatt(g)
                attemptReconnect()
                return
            }

            emit("Service discovery OK — ${g.services.size} services found")

            var foundTx: BluetoothGattCharacteristic? = null
            for (service in g.services) {
                val svcShort = service.uuid.toString().substring(0, 8)
                val charCount = service.characteristics.size
                emit("  SVC $svcShort ($charCount chars)")

                for (char in service.characteristics) {
                    val charShort = char.uuid.toString().substring(0, 8)
                    val props = mutableListOf<String>()
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("R")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("W")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WNR")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("N")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("I")
                    emit("    CHAR $charShort [${props.joinToString(",")}]")

                    val svcId = service.uuid.toString()
                    if ((svcId.startsWith("00001b1b") || svcId.startsWith("00001b1a"))
                        && char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                        foundTx = char
                    }
                }
            }

            if (foundTx == null) {
                emit("TX characteristic NOT FOUND")
                mainHandler.post { connectionCallback.onError(OpenRingError.CharacteristicNotFound) }
                return
            }

            txCharacteristic = foundTx
            emit("TX selected: ${foundTx.uuid.toString().substring(0, 8)} on svc ${foundTx.service.uuid.toString().substring(0, 8)}")

            val notifyChars = g.services
                .flatMap { it.characteristics }
                .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }

            emit("Subscribing to ${notifyChars.size} NOTIFY characteristics...")

            synchronized(pendingSubscriptions) {
                pendingSubscriptions.clear()
                pendingSubscriptions.addAll(notifyChars)
            }
            subscribeNext(g)
        }

        private fun subscribeNext(g: BluetoothGatt) {
            val char: BluetoothGattCharacteristic?
            synchronized(pendingSubscriptions) {
                char = if (pendingSubscriptions.isNotEmpty()) pendingSubscriptions.removeAt(0) else null
            }

            if (char == null) {
                // Relax our connection interval to reduce radio drain. We are a
                // secondary client; gesture/notification latency tolerates the
                // longer interval. (Effect is device-dependent; harmless if ignored.)
                requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                emit("All CCCD subscriptions complete — connection ready")
                mainHandler.post { connectionCallback.onConnected() }
                return
            }

            try {
                g.setCharacteristicNotification(char, true)
            } catch (e: SecurityException) {
                emit("  CCCD SecurityException: ${e.message}")
                subscribeNext(g)
                return
            }

            val cccd = char.getDescriptor(RingProtocol.CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            } else {
                subscribeNext(g)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int
        ) {
            val charShort = desc.characteristic.uuid.toString().substring(0, 8)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                emit("  CCCD OK: $charShort")
            } else {
                emit("  CCCD FAIL: $charShort (status=$status)")
            }
            subscribeNext(g)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray
        ) {
            val hex = value.joinToString(" ") { "%02x".format(it) }
            val charShort = char.uuid.toString().substring(0, 8)
            val channelName = if (value.size >= 2) decodeChannel(value[0], value[1]) else null

            // Tap for the debug bridge: see every raw notification regardless of type.
            OpenRing.rawListener?.invoke(charShort, value)

            // Battery telemetry: log the ring's own battery, tagged with whether we
            // had gestures enabled, so a drain A/B (app armed vs stopped) is possible.
            maybeLogBattery(value)

            if (RingProtocol.isGestureNotification(value)) {
                val id = RingProtocol.parseGestureId(value)
                emit("RECV <- GESTURE DETECTED id=$id [$hex] ch=$channelName char=$charShort")
                val event = GestureEvent(gestureId = id)
                val listener = gestureListener
                if (listener != null) {
                    mainHandler.post { listener.onGesture(event) }
                }
            } else if (RingProtocol.isGestureEnableResponse(value)) {
                val success = RingProtocol.isResponseSuccess(value)
                emit("RECV <- GESTURE_ENABLE_ACK success=$success [$hex] char=$charShort")
            } else if (RingProtocol.isGestureDisableResponse(value)) {
                val success = RingProtocol.isResponseSuccess(value)
                if (disableRequested) {
                    emit("RECV <- GESTURE_DISABLE_ACK success=$success [$hex] char=$charShort (our request)")
                    disableRequested = false
                } else if (gesturesDesired) {
                    // Samsung's app duty-cycles gesture detection OFF to save the
                    // ring's battery (the pinch detector keeps the IMU sampling in a
                    // high-power mode). We deliberately DO NOT override this anymore:
                    // re-enabling here created a tug-of-war that pinned the IMU on
                    // continuously and drained the ring in ~1 day. Respect the disable
                    // and clear our desire flag; the app layer re-enables on the next
                    // genuine trigger activation.
                    //
                    // Firmware RE corroborates this is the right (and only) host-side
                    // battery lever: gesture detection rides the accelerometer's WOM
                    // (wake-on-motion) gate on CH22, so forcing it on bounds the ring's
                    // IMU duty cycle. The larger consumer — the PPG/SpO2 LED+AFE duty
                    // cycle — is owned by Samsung Health and is NOT reachable from here,
                    // so there is nothing further this app can do about it.
                    emit("RECV <- GESTURE_DISABLE_ACK success=$success [$hex] char=$charShort (EXTERNAL — Samsung disabled gestures; respecting it to save ring battery)")
                    gesturesDesired = false
                } else {
                    emit("RECV <- GESTURE_DISABLE_ACK success=$success [$hex] char=$charShort")
                }
            } else if (channelName != null) {
                emit("RECV <- $channelName [$hex] char=$charShort")
            } else {
                emit("RECV <- RAW [$hex] char=$charShort")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, char: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = char.value ?: return
            onCharacteristicChanged(g, char, value)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            val short = char.uuid.toString().substring(0, 8)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readResults[char.uuid.toString()] = value
                emit("READ <- $short [${value.joinToString(" ") { "%02x".format(it) }}]")
            } else {
                emit("READ FAIL $short (status=$status)")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int
        ) {
            @Suppress("DEPRECATION")
            val value = char.value ?: ByteArray(0)
            onCharacteristicRead(g, char, value, status)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int
        ) {
            val charShort = char.uuid.toString().substring(0, 8)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                emit("WRITE OK on $charShort")
            } else {
                emit("WRITE FAILED on $charShort (status=$status)")
            }
        }
    }

    /**
     * Decode CH11 MSG_BATTERY_INFO (0b 0b 02 ...) and emit a structured telemetry
     * line. Param 5 = battery level (0-100), param 6 = charging. rawHex is always
     * logged so a wrong offset is visible rather than silently miscounted.
     */
    private fun maybeLogBattery(value: ByteArray) {
        if (value.size < 5) return
        // Match both the request header 0x02 and the ring's response header 0x42
        // (confirmed live: the ring replies 0b 0b 42 ... to a 0b 0b 03 sync).
        if ((value[0].toInt() and 0xFF) != 0x0b ||
            (value[1].toInt() and 0xFF) != 0x0b ||
            (value[2].toInt() and 0x3f) != 0x02) return

        var level = -1
        var charging = false
        var i = 3
        while (i + 1 < value.size) {
            val id = value[i].toInt() and 0xFF
            val v = value[i + 1].toInt() and 0xFF
            if (id == 5) level = v
            if (id == 6) charging = v != 0
            i += 2
        }
        if (level in 0..100) {
            val hex = value.joinToString(" ") { "%02x".format(it) }
            emit("RING BATTERY level=$level charging=$charging gestures=$gesturesDesired [$hex]")
        }
    }

    private fun decodeChannel(b0: Byte, b1: Byte): String? {
        if (b0 != b1) return null
        return when (b0.toInt() and 0xFF) {
            0x01 -> "CH1:Control"
            0x02 -> "CH2:FindRing"
            0x0a -> "CH10:Health"
            0x0b -> "CH11:Settings"
            0x0c -> "CH12:Debug"
            0x14 -> "CH20:Logging"
            0x15 -> "CH21:FOTA"
            0x16 -> "CH22:Gesture"
            0x17 -> "CH23:Heartbeat"
            0x1f -> "CH31:FindDevice"
            0x20 -> "CH32:Text"
            0x21 -> "CH33:RawPPG"   // firmware-confirmed: raw PPG/HRM sensor control
            else -> "CH${b0.toInt() and 0xFF}"
        }
    }

    private fun emit(message: String) {
        Log.d(TAG, message)
        OpenRing.logger?.log(message)
    }
}
