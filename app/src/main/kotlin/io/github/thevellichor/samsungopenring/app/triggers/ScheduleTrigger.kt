package io.github.thevellichor.samsungopenring.app.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.util.Calendar

class ScheduleTrigger(
    private val startHour: Int,
    private val startMinute: Int,
    private val endHour: Int,
    private val endMinute: Int,
    private val daysOfWeek: Set<Int>, // Calendar.MONDAY..SUNDAY
) : Trigger {

    companion object {
        private const val TAG = "OpenRing.Schedule"
        private const val ACTION_START = "io.github.thevellichor.samsungopenring.SCHEDULE_START"
        private const val ACTION_END = "io.github.thevellichor.samsungopenring.SCHEDULE_END"
        private const val ACTION_CHECK = "io.github.thevellichor.samsungopenring.SCHEDULE_CHECK"
    }

    override val id = "schedule_${startHour}${startMinute}_${endHour}${endMinute}"
    override val name: String
        get() {
            val days = daysOfWeek.sorted().joinToString(",") { dayName(it) }
            return "Schedule: %02d:%02d–%02d:%02d $days".format(startHour, startMinute, endHour, endMinute)
        }
    override val description = "Activate during scheduled time window"

    private var armed = false
    private var callback: TriggerCallback? = null
    private var active = false
    private var receiver: BroadcastReceiver? = null

    override fun arm(context: Context, callback: TriggerCallback) {
        if (armed) return
        this.callback = callback

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_START, ACTION_CHECK -> {
                        if (isInWindow() && !active) {
                            Log.d(TAG, "Schedule window active")
                            active = true
                            callback.onActivated(this@ScheduleTrigger)
                        }
                        scheduleNextAlarms(ctx)
                    }
                    ACTION_END -> {
                        if (active) {
                            Log.d(TAG, "Schedule window ended")
                            active = false
                            callback.onDeactivated(this@ScheduleTrigger)
                        }
                        scheduleNextAlarms(ctx)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_END)
            addAction(ACTION_CHECK)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Check if currently in window
        if (isInWindow()) {
            Log.d(TAG, "Currently in schedule window")
            active = true
            callback.onActivated(this)
        }

        scheduleNextAlarms(context)
        armed = true
        Log.d(TAG, "Armed: $name")
    }

    override fun disarm(context: Context) {
        if (!armed) return
        receiver?.let { context.unregisterReceiver(it) }
        cancelAlarms(context)
        receiver = null
        callback = null
        armed = false
        active = false
        Log.d(TAG, "Disarmed")
    }

    override fun isArmed() = armed

    private fun isInWindow(): Boolean {
        val now = Calendar.getInstance()
        val day = now.get(Calendar.DAY_OF_WEEK)
        if (daysOfWeek.isNotEmpty() && day !in daysOfWeek) return false

        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute

        return if (endMinutes > startMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            // Overnight window (e.g. 22:00-06:00)
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }

    private fun scheduleNextAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val startIntent = PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(ACTION_START), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val endIntent = PendingIntent.getBroadcast(
            context, id.hashCode() + 1,
            Intent(ACTION_END), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextStart = nextOccurrence(startHour, startMinute)
        val nextEnd = nextOccurrence(endHour, endMinute)

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextStart, startIntent)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextEnd, endIntent)
    }

    private fun cancelAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val startIntent = PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(ACTION_START), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        val endIntent = PendingIntent.getBroadcast(
            context, id.hashCode() + 1,
            Intent(ACTION_END), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        startIntent?.let { alarmManager.cancel(it) }
        endIntent?.let { alarmManager.cancel(it) }
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun dayName(day: Int): String = when (day) {
        Calendar.MONDAY -> "Mon"
        Calendar.TUESDAY -> "Tue"
        Calendar.WEDNESDAY -> "Wed"
        Calendar.THURSDAY -> "Thu"
        Calendar.FRIDAY -> "Fri"
        Calendar.SATURDAY -> "Sat"
        Calendar.SUNDAY -> "Sun"
        else -> "?"
    }
}
