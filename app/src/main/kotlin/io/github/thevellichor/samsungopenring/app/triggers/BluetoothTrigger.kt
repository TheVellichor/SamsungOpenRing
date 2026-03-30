package io.github.thevellichor.samsungopenring.app.triggers

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

class BluetoothTrigger(
    private val targetAddress: String,
    private val targetName: String,
) : Trigger {

    companion object {
        private const val TAG = "OpenRing.BtTrigger"
    }

    override val id = "bluetooth_$targetAddress"
    override val name = "Bluetooth: $targetName"
    override val description = "Activate when $targetName connects"

    private var armed = false
    private var receiver: BroadcastReceiver? = null
    private var callback: TriggerCallback? = null
    private var active = false

    override fun arm(context: Context, callback: TriggerCallback) {
        if (armed) return
        this.callback = callback

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = intent.getParcelableExtra<BluetoothDevice>(
                    BluetoothDevice.EXTRA_DEVICE
                ) ?: return

                if (!device.address.equals(targetAddress, ignoreCase = true)) return

                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        Log.d(TAG, "Target device connected: ${device.name}")
                        if (!active) {
                            active = true
                            callback.onActivated(this@BluetoothTrigger)
                        }
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        Log.d(TAG, "Target device disconnected: ${device.name}")
                        if (active) {
                            active = false
                            callback.onDeactivated(this@BluetoothTrigger)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)
        armed = true
        Log.d(TAG, "Armed for $targetName ($targetAddress)")
    }

    override fun disarm(context: Context) {
        if (!armed) return
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
        callback = null
        armed = false
        active = false
        Log.d(TAG, "Disarmed")
    }

    override fun isArmed() = armed
}
