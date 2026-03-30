package io.github.thevellichor.samsungopenring.app

import android.util.Log
import io.github.thevellichor.samsungopenring.core.GestureEvent
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WebhookSender {

    private const val TAG = "OpenRing.Webhook"
    private val client = OkHttpClient()
    private val JSON = "application/json".toMediaType()

    fun send(
        url: String,
        event: GestureEvent,
        triggerName: String = "manual",
        callback: ((success: Boolean, detail: String) -> Unit)? = null,
    ) {
        if (url.isBlank()) {
            Log.w(TAG, "No webhook URL configured")
            callback?.invoke(false, "no URL configured")
            return
        }

        val timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(event.timestamp))

        val json = """
            {
                "gesture": "double_pinch",
                "gesture_id": ${event.gestureId},
                "timestamp": "$timestamp",
                "trigger": "$triggerName",
                "device": "SamsungOpenRing"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Webhook failed: ${e.message}")
                callback?.invoke(false, e.message ?: "unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    Log.d(TAG, "Webhook sent: ${response.code}")
                    callback?.invoke(response.isSuccessful, "HTTP ${response.code}")
                }
            }
        })
    }
}
