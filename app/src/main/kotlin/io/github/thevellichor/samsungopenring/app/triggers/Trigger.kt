package io.github.thevellichor.samsungopenring.app.triggers

import android.content.Context

interface Trigger {
    val id: String
    val name: String
    val description: String

    fun arm(context: Context, callback: TriggerCallback)
    fun disarm(context: Context)
    fun isArmed(): Boolean
}

interface TriggerCallback {
    fun onActivated(trigger: Trigger)
    fun onDeactivated(trigger: Trigger)
}
