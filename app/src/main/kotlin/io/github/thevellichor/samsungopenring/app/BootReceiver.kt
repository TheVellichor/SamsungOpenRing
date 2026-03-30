package io.github.thevellichor.samsungopenring.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.thevellichor.samsungopenring.app.triggers.TriggerManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OpenRing.Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed — re-arming triggers")
        EventLog.log(context, "Device booted — re-arming triggers")

        val triggerManager = TriggerManager(context)
        val configs = triggerManager.getConfiguredTriggers()

        if (configs.isNotEmpty()) {
            triggerManager.armAll()
            EventLog.log(context, "Re-armed ${configs.size} triggers after boot")
        } else {
            EventLog.log(context, "No triggers configured, nothing to re-arm")
        }
    }
}
