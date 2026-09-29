package io.github.thevellichor.samsungopenring.core

/**
 * Parsed Channel 11 battery report from [RingSettingsChannel.parseBattery].
 *
 * @param level percent 0–100, or -1 when unknown
 * @param charging true when the ring reports active charging
 */
data class RingBatteryStatus(
    val level: Int = -1,
    val charging: Boolean = false,
)
