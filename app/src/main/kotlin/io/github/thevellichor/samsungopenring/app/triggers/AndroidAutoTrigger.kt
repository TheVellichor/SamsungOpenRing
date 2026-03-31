package io.github.thevellichor.samsungopenring.app.triggers

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.util.Log

class AndroidAutoTrigger : Trigger {

    companion object {
        private const val TAG = "OpenRing.AAuto"
        private const val ACTION_CAR_CONNECTED = "com.google.android.gms.car.connected"
        private const val ACTION_CAR_DISCONNECTED = "com.google.android.gms.car.disconnected"
    }

    override val id = "android_auto"
    override val name = "Android Auto"
    override val description = "Activate when connected to Android Auto"

    private var armed = false
    private var receiver: BroadcastReceiver? = null
    private var callback: TriggerCallback? = null
    private var active = false

    override fun arm(context: Context, callback: TriggerCallback) {
        if (armed) return
        this.callback = callback

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_CAR_CONNECTED -> {
                        Log.d(TAG, "Android Auto connected")
                        if (!active) {
                            active = true
                            callback.onActivated(this@AndroidAutoTrigger)
                        }
                    }
                    ACTION_CAR_DISCONNECTED -> {
                        Log.d(TAG, "Android Auto disconnected")
                        if (active) {
                            active = false
                            callback.onDeactivated(this@AndroidAutoTrigger)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_CAR_CONNECTED)
            addAction(ACTION_CAR_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)

        // Check if already in car mode
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR) {
            Log.d(TAG, "Already in car mode")
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
