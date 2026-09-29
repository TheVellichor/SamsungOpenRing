package io.github.thevellichor.samsungopenring.core

/**
 * Samsung Accessory Protocol Channel 11 (settings): battery sync and factory reset.
 *
 * Wire format per RFC-SGR-001 §6.3 and §10. Message ids use the lower six bits of
 * the third octet (response frames set additional flags in that byte).
 */
object RingSettingsChannel {

    const val CHANNEL_SETTINGS: Int = 0x0b

    /** MSG_BATTERY_INFO — ring → host structured battery TLVs. */
    const val MSG_BATTERY_INFO: Int = 2

    /** MSG_SEND_BATTERY_SYNC — host → ring; ring answers with MSG_BATTERY_INFO. */
    const val MSG_SEND_BATTERY_SYNC: Int = 3

    /** MSG_RESET_RING — host factory reset command and matching acknowledgement id. */
    const val MSG_RESET_RING: Int = 33

    /** Three-byte battery sync request: `0x0b 0x0b 0x03`. */
    val batterySyncRequest: ByteArray = channelMessage(CHANNEL_SETTINGS, MSG_SEND_BATTERY_SYNC)

    /** Channel 11 factory-reset command (destructive). */
    val resetRingRequest: ByteArray = channelMessage(CHANNEL_SETTINGS, MSG_RESET_RING)

    /** Lower six bits of the SAP message-id octet. */
    fun sapMessageId(octet: Byte): Int = octet.toInt() and 0x3f

    /** True when [payload] is a doubled Channel 11 envelope. */
    fun isSettingsChannel(payload: ByteArray): Boolean {
        if (payload.size < 2) return false
        return (payload[0].toInt() and 0xff) == CHANNEL_SETTINGS &&
            (payload[1].toInt() and 0xff) == CHANNEL_SETTINGS
    }

    /**
     * Builds a three-byte settings-channel command: `[ch][ch][msgId]`.
     */
    fun channelMessage(channel: Int, messageId: Int): ByteArray =
        byteArrayOf(
            channel.toByte(),
            channel.toByte(),
            messageId.toByte(),
        )

    /**
     * Parses MSG_BATTERY_INFO from a notification payload, or null when not a battery report.
     *
     * TLV tags: 5 = level (0–100), 6 = charging (non-zero = true).
     */
    fun parseBattery(payload: ByteArray): RingBatteryStatus? {
        if (!isSettingsChannel(payload) || payload.size < 5) return null
        if (sapMessageId(payload[2]) != MSG_BATTERY_INFO) return null

        var level = -1
        var charging = false
        var index = 3
        while (index + 1 < payload.size) {
            when (payload[index].toInt() and 0xff) {
                5 -> level = payload[index + 1].toInt() and 0xff
                6 -> charging = (payload[index + 1].toInt() and 0xff) != 0
            }
            index += 2
        }
        return if (level in 0..100) {
            RingBatteryStatus(level = level, charging = charging)
        } else {
            null
        }
    }

    /**
     * Parses a factory-reset acknowledgement.
     *
     * @return true on success (status octet at index 4 is 0x00), false on explicit failure,
     *   or null when the payload is not a reset reply or is too short to classify as NACK.
     */
    fun parseResetAck(payload: ByteArray): Boolean? {
        if (!isSettingsChannel(payload) || payload.size < 3) return null
        if (sapMessageId(payload[2]) != MSG_RESET_RING) return null
        if (payload.size < 5) return null
        return payload[4] == 0x00.toByte()
    }
}
