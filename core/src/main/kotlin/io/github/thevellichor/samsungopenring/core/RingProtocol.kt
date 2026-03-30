package io.github.thevellichor.samsungopenring.core

import java.util.UUID

internal object RingProtocol {

    // GATT Service UUIDs
    val DATA_SERVICE_UUID: UUID = UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb")
    val FOTA_SERVICE_UUID: UUID = UUID.fromString("00001b1a-0000-1000-8000-00805f9b34fb")

    // GATT Characteristic UUIDs
    val TX_UUID: UUID = UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b")
    val RX_UUID: UUID = UUID.fromString("63e30bad-4206-4596-839f-e47cbf7a4b5d")

    // CCCD for enabling notifications
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Channel 22 (gesture) envelope prefix
    private const val GESTURE_CHANNEL: Byte = 0x16

    // Gesture commands: [channel][channel][msgId]
    val CMD_ENABLE_GESTURES = byteArrayOf(GESTURE_CHANNEL, GESTURE_CHANNEL, 0x00)
    val CMD_DISABLE_GESTURES = byteArrayOf(GESTURE_CHANNEL, GESTURE_CHANNEL, 0x01)

    // Gesture detection MSG_ID
    private const val MSG_PINCH_DETECTION: Byte = 0x02

    // Gesture enable response: 0x16 0x16 0x40 0x00 0x01
    private const val MSG_ENABLE_RESPONSE: Byte = 0x40

    // Gesture disable response: 0x16 0x16 0x41 0x01 0x01
    private const val MSG_DISABLE_RESPONSE: Byte = 0x41

    fun isGestureNotification(value: ByteArray): Boolean {
        return value.size >= 3
            && value[0] == GESTURE_CHANNEL
            && value[1] == GESTURE_CHANNEL
            && value[2] == MSG_PINCH_DETECTION
    }

    fun parseGestureId(value: ByteArray): Int {
        return if (value.size > 3) value[3].toInt() and 0xFF else -1
    }

    fun isGestureEnableResponse(value: ByteArray): Boolean {
        return value.size >= 5
            && value[0] == GESTURE_CHANNEL
            && value[1] == GESTURE_CHANNEL
            && value[2] == MSG_ENABLE_RESPONSE
    }

    fun isGestureDisableResponse(value: ByteArray): Boolean {
        return value.size >= 5
            && value[0] == GESTURE_CHANNEL
            && value[1] == GESTURE_CHANNEL
            && value[2] == MSG_DISABLE_RESPONSE
    }

    fun isResponseSuccess(value: ByteArray): Boolean {
        return value.size >= 5 && value[4] == 0x01.toByte()
    }
}
