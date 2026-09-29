package io.github.thevellichor.samsungopenring.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingSettingsChannelTest {

    @Test
    fun parseBattery_fromLiveWireExample() {
        val payload = byteArrayOf(
            0x0b,
            0x0b,
            0x42,
            0x06,
            0x00,
            0x05,
            0x3f,
            0x07,
            0xff.toByte(),
            0x08,
            0xff.toByte(),
        )
        val battery = RingSettingsChannel.parseBattery(payload)
        assertNotNull(battery)
        assertEquals(63, battery!!.level)
        assertFalse(battery.charging)
    }

    @Test
    fun parseResetAck_success() {
        val payload = byteArrayOf(0x0b, 0x0b, 0x21, 0x00, 0x00)
        assertTrue(RingSettingsChannel.parseResetAck(payload) == true)
    }

    @Test
    fun parseResetAck_shortPayloadReturnsNull() {
        val payload = byteArrayOf(0x0b, 0x0b, 0x21, 0x00)
        assertEquals(null, RingSettingsChannel.parseResetAck(payload))
    }

    @Test
    fun parseResetAck_nonZeroStatusReturnsFalse() {
        val payload = byteArrayOf(0x0b, 0x0b, 0x21, 0x00, 0x01)
        assertFalse(RingSettingsChannel.parseResetAck(payload)!!)
    }

    @Test
    fun batterySyncRequest_matchesRfcEnvelope() {
        assertEquals(
            listOf(0x0b, 0x0b, 0x03),
            RingSettingsChannel.batterySyncRequest.map { it.toInt() and 0xff },
        )
    }
}
