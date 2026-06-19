package io.github.thevellichor.samsungopenring.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * DEBUG-ONLY Application. Auto-starts the Claude Bridge when any activity resumes,
 * so simply opening the app (or `adb shell am start -n <pkg>/.MainActivity`) brings
 * the bridge up. Starting a foreground service from a resumed activity is always
 * permitted, so this avoids Android's background-FGS-start restriction.
 *
 * Only present in debug builds (debug source set), so release builds use the
 * default Application and never include the bridge.
 */
class ClaudeBridgeApp : Application() {

    private var started = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (started) return
                started = true
                try {
                    startForegroundService(Intent(this@ClaudeBridgeApp, ClaudeBridgeService::class.java))
                    Log.d("OpenRing.Bridge", "Auto-started Claude Bridge from ${activity.javaClass.simpleName}")
                } catch (e: Exception) {
                    started = false
                    Log.w("OpenRing.Bridge", "Auto-start failed: ${e.message}")
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
