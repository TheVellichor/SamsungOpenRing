package io.github.thevellichor.samsungopenring.app.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

class ChargingTrigger : Trigger {

    companion object {
        private const val TAG = "OpenRing.Charging"
    }

    override val id = "charging"
    override val name = "Charging"
    override val description = "Activate when phone is plugged in"

    private var armed = false
    private var callback: TriggerCallback? = null
    private var active = false
    private var receiver: BroadcastReceiver? = null

    override fun arm(context: Context, callback: TriggerCallback) {
        if (armed) return
        this.callback = callback

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        if (!active) {
                            Log.d(TAG, "Power connected")
                            active = true
                            callback.onActivated(this@ChargingTrigger)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        if (active) {
                            Log.d(TAG, "Power disconnected")
                            active = false
                            callback.onDeactivated(this@ChargingTrigger)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)

        // Check current charging state
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (batteryManager.isCharging) {
            Log.d(TAG, "Currently charging")
            active = true
            callback.onActivated(this)
        }

        armed = true
        Log.d(TAG, "Armed")
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
