package io.github.thevellichor.samsungopenring.tasker

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.thevellichor.samsungopenring.core.*

class TaskerGestureService : Service() {

    companion object {
        private const val TAG = "OpenRing.Tasker"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "openring_tasker"
        const val ACTION_GESTURE = "io.github.thevellichor.samsungopenring.tasker.GESTURE"
        const val EXTRA_GESTURE_ID = "gesture_id"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TaskerGestureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TaskerGestureService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        Log.d(TAG, "Tasker service starting")

        OpenRing.connect(applicationContext, object : ConnectionCallback {
            override fun onConnected() {
                Log.d(TAG, "Connected, enabling gestures for Tasker")
                updateNotification("Monitoring gestures for Tasker")

                OpenRing.enableGestures { event ->
                    Log.d(TAG, "Gesture #${event.gestureId} -> broadcasting to Tasker")
                    updateNotification("Gesture #${event.gestureId}")

                    // Broadcast intent that Tasker's QueryReceiver picks up
                    val intent = Intent(ACTION_GESTURE).apply {
                        putExtra(EXTRA_GESTURE_ID, event.gestureId)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)

                    // Also fire the Tasker condition satisfied intent
                    val taskerIntent = Intent(
                        "com.twofortyfouram.locale.intent.action.REQUEST_QUERY"
                    ).apply {
                        putExtra("com.twofortyfouram.locale.intent.extra.ACTIVITY",
                            EditActivity::class.java.name)
                    }
                    sendBroadcast(taskerIntent)
                }
            }

            override fun onDisconnected() {
                Log.d(TAG, "Disconnected")
                updateNotification("Disconnected — reconnecting...")
            }

            override fun onError(error: OpenRingError) {
                Log.e(TAG, "Error: ${error.message}")
                updateNotification("Error: ${error.message}")
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        OpenRing.disableGestures()
        OpenRing.disconnect()
        Log.d(TAG, "Tasker service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Tasker Gesture Monitor", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active when Tasker is monitoring ring gestures" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenRing Tasker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
