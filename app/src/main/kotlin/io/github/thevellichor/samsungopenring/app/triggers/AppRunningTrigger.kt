package io.github.thevellichor.samsungopenring.app.triggers

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

class AppRunningTrigger(
    private val targetPackage: String,
    private val appLabel: String,
) : Trigger {

    companion object {
        private const val TAG = "OpenRing.AppTrigger"
        private const val POLL_INTERVAL_MS = 15_000L // 15 seconds
    }

    override val id = "app_$targetPackage"
    override val name = "App: $appLabel"
    override val description = "Activate when $appLabel is in foreground"

    private var armed = false
    private var callback: TriggerCallback? = null
    private var active = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var armContext: Context? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!armed) return
            checkForegroundApp()
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun arm(context: Context, callback: TriggerCallback) {
        if (armed) return
        this.callback = callback
        this.armContext = context.applicationContext

        handlerThread = HandlerThread("AppTrigger").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        handler?.post(pollRunnable)

        armed = true
        Log.d(TAG, "Armed for $appLabel ($targetPackage)")
    }

    override fun disarm(context: Context) {
        if (!armed) return
        armed = false
        handler?.removeCallbacks(pollRunnable)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        callback = null
        armContext = null
        active = false
        Log.d(TAG, "Disarmed")
    }

    override fun isArmed() = armed

    private fun checkForegroundApp() {
        val ctx = armContext ?: return
        val usageStatsManager = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return

        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 30_000,
            now
        )

        val foreground = stats
            ?.filter { it.lastTimeUsed > now - 20_000 }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName

        val isTarget = foreground == targetPackage

        if (isTarget && !active) {
            Log.d(TAG, "$appLabel is in foreground")
            active = true
            callback?.onActivated(this)
        } else if (!isTarget && active) {
            Log.d(TAG, "$appLabel left foreground")
            active = false
            callback?.onDeactivated(this)
        }
    }
}
