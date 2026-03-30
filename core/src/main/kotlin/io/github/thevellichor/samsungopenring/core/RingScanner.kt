package io.github.thevellichor.samsungopenring.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

internal object RingScanner {

    private const val TAG = "OpenRing.Scanner"

    // Samsung Galaxy Ring advertises as "Galaxy Ring (XXXX)"
    private val GALAXY_RING_PATTERN = Regex("Galaxy Ring", RegexOption.IGNORE_CASE)

    fun findBondedRing(context: Context): BluetoothDevice? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter

        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter")
            return null
        }

        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            return null
        }

        // Primary match: BLE device with "Galaxy Ring" in name
        val ring = adapter.bondedDevices?.find { device ->
            device.type == BluetoothDevice.DEVICE_TYPE_LE
                && device.name?.contains(GALAXY_RING_PATTERN) == true
        }

        if (ring != null) {
            Log.d(TAG, "Found bonded ring: ${ring.name} (${ring.address})")
        } else {
            Log.d(TAG, "No Galaxy Ring found in ${adapter.bondedDevices?.size ?: 0} bonded devices")
        }

        return ring
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }
}
