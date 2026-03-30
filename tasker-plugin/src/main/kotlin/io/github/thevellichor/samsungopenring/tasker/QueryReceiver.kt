package io.github.thevellichor.samsungopenring.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Tasker queries this receiver to check if the event condition is satisfied.
 * We report satisfied whenever a gesture was recently detected.
 */
class QueryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OpenRing.TaskerQuery"
        private const val RESULT_CONDITION_SATISFIED = 16
        private const val RESULT_CONDITION_UNSATISFIED = 17

        @Volatile
        var lastGestureTime: Long = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val recentGesture = (now - lastGestureTime) < 5_000 // within 5 seconds

        Log.d(TAG, "Query: recentGesture=$recentGesture (${now - lastGestureTime}ms ago)")

        resultCode = if (recentGesture) {
            lastGestureTime = 0 // consume the event
            RESULT_CONDITION_SATISFIED
        } else {
            RESULT_CONDITION_UNSATISFIED
        }
    }
}
