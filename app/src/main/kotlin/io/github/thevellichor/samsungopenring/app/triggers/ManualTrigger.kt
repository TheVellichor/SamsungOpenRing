package io.github.thevellichor.samsungopenring.app.triggers

import android.content.Context
import android.util.Log

class ManualTrigger : Trigger {

    companion object {
        private const val TAG = "OpenRing.ManualTrigger"
    }

    override val id = "manual"
    override val name = "Manual"
    override val description = "Activate manually via button"

    private var armed = false
    private var callback: TriggerCallback? = null

    override fun arm(context: Context, callback: TriggerCallback) {
        this.callback = callback
        armed = true
        Log.d(TAG, "Armed")
    }

    override fun disarm(context: Context) {
        armed = false
        callback = null
        Log.d(TAG, "Disarmed")
    }

    override fun isArmed() = armed

    fun activate() {
        Log.d(TAG, "Manually activated")
        callback?.onActivated(this)
    }

    fun deactivate() {
        Log.d(TAG, "Manually deactivated")
        callback?.onDeactivated(this)
    }
}
