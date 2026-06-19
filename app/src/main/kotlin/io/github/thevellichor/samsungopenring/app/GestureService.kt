package io.github.thevellichor.samsungopenring.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.thevellichor.samsungopenring.core.*

class GestureService : Service() {

    companion object {
        private const val TAG = "OpenRing.Service"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "openring_gesture"
        private const val PREFS_NAME = "openring_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"

        // Absolute safety backstop. Gesture detection stays on for the GENUINE
        // trigger window (owned by TriggerManager's refcount: start on the first
        // activation, stop when the last trigger deactivates). This cap is NOT a
        // feature limiter and NOT a sliding timer — it is a hard ceiling, measured
        // from when gestures were first enabled, that bounds a STUCK window
        // (a forgotten manual session, or a trigger that never fires its OFF edge).
        // It survives BLE reconnects and process restarts via a persisted deadline,
        // and is never extended by reconnects, pinches, or repeated activations.
        // Default 8h covers a 9-5 schedule / long drive / home-WiFi window.
        private const val KEY_DEADLINE = "session_deadline_epoch_ms"
        private const val KEY_MAX_SESSION_MS = "max_session_ms"
        private const val DEFAULT_MAX_SESSION_MS = 8L * 60 * 60 * 1000 // 8 hours

        // Written by TriggerManager: how many triggers are currently active. When the
        // cap fires, a value > 0 means this is a genuinely long live window (e.g. a
        // full-day schedule), so we renew rather than kill the feature; 0 means a
        // forgotten/manual/stuck window, so we stop.
        const val KEY_ACTIVE_TRIGGERS = "active_trigger_count"
        const val ACTIVE_TRIGGERS_PREFS = "openring_prefs" // must match PREFS_NAME

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GestureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureService::class.java))
        }
    }

    private var webhookUrl: String = ""

    /** True while we have asked the ring to keep gesture detection on. */
    private var gesturesActive = false

    private val capHandler = Handler(Looper.getMainLooper())
    private val capRunnable = Runnable { onSessionCapReached() }

    /** Single reusable gesture listener so we can re-enable on a fresh connect. */
    private val gestureCallback = GestureListener { event ->
        log("GESTURE #${event.gestureId} detected")
        updateNotification("Gesture #${event.gestureId} detected")
        // NOTE: a detected pinch deliberately does NOT extend the safety cap.

        val url = webhookUrl
        if (url.isNotBlank()) {
            log("Sending webhook -> $url")
            WebhookSender.send(url, event) { success, detail ->
                if (success) {
                    log("Webhook OK ($detail)")
                } else {
                    log("Webhook FAILED: $detail")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        OpenRing.logger = OpenRingLogger { message ->
            EventLog.log(this@GestureService, message)
        }

        webhookUrl = getWebhookUrl()
        log("Service started (webhook: ${if (webhookUrl.isNotBlank()) webhookUrl else "none"})")

        // Arm the backstop immediately so even a service that never connects
        // (ring out of range / BT off) still self-stops instead of orphaning.
        armSessionCap()
        connectAndEnable()
    }

    // A fresh start command (trigger (re)activation or manual press) does NOT reset
    // the safety cap — that is the whole point of an absolute ceiling. The window is
    // owned by TriggerManager's refcount, so there is nothing to do here.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun connectAndEnable() {
        log("Connecting to Galaxy Ring...")
        updateNotification("Connecting to ring...")

        OpenRing.connect(applicationContext, object : ConnectionCallback {
            override fun onConnected() {
                log("BLE connected — enabling gestures")
                OpenRing.enableGestures(gestureCallback)
                gesturesActive = true
                armSessionCap()
                updateNotification("Connected — gestures on")
            }

            override fun onDisconnected() {
                log("BLE disconnected — auto-reconnect will be attempted by core")
                updateNotification("Disconnected — reconnecting...")
            }

            override fun onError(error: OpenRingError) {
                log("ERROR: ${error.message}")
                updateNotification("Error: ${error.message}")
            }
        })
    }

    /**
     * Arm the absolute session cap from a PERSISTED deadline. Reading (not
     * re-arming) the deadline makes the ceiling survive reconnects and
     * START_STICKY restarts without ever being extended. Only sets a fresh
     * deadline if none exists or the stored one has already passed.
     */
    private fun armSessionCap() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var deadline = prefs.getLong(KEY_DEADLINE, 0L)
        if (deadline <= now) {
            deadline = now + prefs.getLong(KEY_MAX_SESSION_MS, DEFAULT_MAX_SESSION_MS)
            prefs.edit().putLong(KEY_DEADLINE, deadline).apply()
        }
        val remaining = (deadline - now).coerceAtLeast(0)
        capHandler.removeCallbacks(capRunnable)
        capHandler.postDelayed(capRunnable, remaining)
        log("Gesture safety cap armed: ${remaining / 60000}min remaining")
    }

    private fun onSessionCapReached() {
        // If a trigger is still genuinely active, this is a long LIVE window (e.g.
        // a full-day schedule or home-WiFi) — renew the cap instead of killing the
        // feature. Only a forgotten/manual/stuck window (no active trigger) stops.
        val activeTriggers = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_ACTIVE_TRIGGERS, 0)
        if (activeTriggers > 0) {
            log("Safety cap reached but $activeTriggers trigger(s) still active — renewing cap, keeping gestures on")
            clearDeadline()
            armSessionCap()
            return
        }
        log("Gesture safety cap reached — disabling gestures and stopping (forgotten/stuck window backstop)")
        updateNotification("Stopped — safety cap reached")
        clearDeadline()
        gesturesActive = false
        stopSelf()
    }

    private fun clearDeadline() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_DEADLINE).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        capHandler.removeCallbacks(capRunnable)
        gesturesActive = false
        // A clean (trigger-driven or user) stop ends the window, so start a fresh
        // cap next time rather than resuming a stale deadline.
        clearDeadline()
        log("Disabling gestures...")
        OpenRing.disableGestures()
        log("Disconnecting...")
        OpenRing.disconnect()
        log("Service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun log(message: String) {
        Log.d(TAG, message)
        EventLog.log(this, message)
    }

    private fun getWebhookUrl(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_WEBHOOK_URL, "") ?: ""
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gesture Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when SamsungOpenRing is monitoring gestures"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SamsungOpenRing")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
