package io.github.thevellichor.samsungopenring.app.triggers

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

class GeofenceTrigger(
    private val latitude: Double,
    private val longitude: Double,
    private val radiusMeters: Float,
    private val label: String,
) : Trigger {

    companion object {
        private const val TAG = "OpenRing.Geofence"
        private const val MIN_INTERVAL_MS = 300_000L // 5 minutes
        private const val MIN_DISTANCE_M = 50f
    }

    override val id = "geofence_${latitude}_${longitude}"
    override val name = "Location: $label"
    override val description = "Activate within ${radiusMeters.toInt()}m of $label"

    private var armed = false
    private var callback: TriggerCallback? = null
    private var active = false
    private var locationListener: LocationListener? = null

    override fun arm(context: Context, callback: TriggerCallback) {
        if (armed) return
        this.callback = callback

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val target = Location("").apply {
                    this.latitude = this@GeofenceTrigger.latitude
                    this.longitude = this@GeofenceTrigger.longitude
                }
                val distance = location.distanceTo(target)
                val inside = distance <= radiusMeters

                if (inside && !active) {
                    Log.d(TAG, "Entered geofence: $label (${distance.toInt()}m)")
                    active = true
                    callback.onActivated(this@GeofenceTrigger)
                } else if (!inside && active) {
                    Log.d(TAG, "Left geofence: $label (${distance.toInt()}m)")
                    active = false
                    callback.onDeactivated(this@GeofenceTrigger)
                }
            }

            @Deprecated("Deprecated")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.FUSED_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                locationListener!!
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied")
            return
        }

        // Check current location
        try {
            val lastLocation = locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            if (lastLocation != null) {
                locationListener!!.onLocationChanged(lastLocation)
            }
        } catch (_: SecurityException) {}

        armed = true
        Log.d(TAG, "Armed: $label ($latitude, $longitude, ${radiusMeters}m)")
    }

    override fun disarm(context: Context) {
        if (!armed) return
        locationListener?.let {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(it)
        }
        locationListener = null
        callback = null
        armed = false
        active = false
        Log.d(TAG, "Disarmed")
    }

    override fun isArmed() = armed
}
