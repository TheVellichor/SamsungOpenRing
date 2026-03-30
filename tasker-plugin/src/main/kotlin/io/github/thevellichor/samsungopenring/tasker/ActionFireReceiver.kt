package io.github.thevellichor.samsungopenring.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Tasker fires this receiver to execute the enable/disable action.
 */
class ActionFireReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OpenRing.TaskerAction"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE")
            ?: return

        val action = bundle.getString(ActionEditActivity.EXTRA_ACTION)
            ?: ActionEditActivity.ACTION_ENABLE

        Log.d(TAG, "Tasker action: $action")

        when (action) {
            ActionEditActivity.ACTION_ENABLE -> {
                TaskerGestureService.start(context)
            }
            ActionEditActivity.ACTION_DISABLE -> {
                TaskerGestureService.stop(context)
            }
        }
    }
}
