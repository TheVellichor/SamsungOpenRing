package io.github.thevellichor.samsungopenring.app.triggers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.thevellichor.samsungopenring.app.GestureService

class TriggerManager(context: Context) {

    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "OpenRing.TriggerMgr"
        private const val PREFS_NAME = "openring_triggers"
        private const val KEY_TRIGGERS = "configured_triggers"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val activeTriggers = mutableListOf<Trigger>()
    private var activeTriggerCount = 0

    private val triggerCallback = object : TriggerCallback {
        override fun onActivated(trigger: Trigger) {
            Log.d(TAG, "Trigger activated: ${trigger.name}")
            activeTriggerCount++
            if (activeTriggerCount == 1) {
                GestureService.start(context)
            }
        }

        override fun onDeactivated(trigger: Trigger) {
            Log.d(TAG, "Trigger deactivated: ${trigger.name}")
            activeTriggerCount = (activeTriggerCount - 1).coerceAtLeast(0)
            if (activeTriggerCount == 0) {
                GestureService.stop(context)
            }
        }
    }

    fun armAll() {
        val configs = loadConfigs()
        for (config in configs) {
            val trigger = createTrigger(config) ?: continue
            trigger.arm(context, triggerCallback)
            activeTriggers.add(trigger)
        }
        Log.d(TAG, "Armed ${activeTriggers.size} triggers")
    }

    fun disarmAll() {
        for (trigger in activeTriggers) {
            trigger.disarm(context)
        }
        activeTriggers.clear()
        activeTriggerCount = 0
        Log.d(TAG, "All triggers disarmed")
    }

    fun addBluetoothTrigger(address: String, name: String) {
        val configs = loadConfigs().toMutableList()
        val config = TriggerConfig(type = "bluetooth", address = address, name = name)

        if (configs.any { it.type == "bluetooth" && it.address == address }) {
            Log.d(TAG, "Bluetooth trigger already exists for $address")
            return
        }

        configs.add(config)
        saveConfigs(configs)

        val trigger = BluetoothTrigger(address, name)
        trigger.arm(context, triggerCallback)
        activeTriggers.add(trigger)
        Log.d(TAG, "Added bluetooth trigger: $name ($address)")
    }

    fun removeTrigger(id: String) {
        val trigger = activeTriggers.find { it.id == id }
        trigger?.disarm(context)
        activeTriggers.removeAll { it.id == id }

        val configs = loadConfigs().toMutableList()
        configs.removeAll { createTriggerId(it) == id }
        saveConfigs(configs)
    }

    fun getConfiguredTriggers(): List<TriggerConfig> = loadConfigs()

    fun getActiveTriggers(): List<Trigger> = activeTriggers.toList()

    fun addWifiTrigger(ssid: String) {
        val configs = loadConfigs().toMutableList()
        if (configs.any { it.type == "wifi" && it.name == ssid }) {
            Log.d(TAG, "WiFi trigger already exists for $ssid")
            return
        }

        configs.add(TriggerConfig(type = "wifi", name = ssid))
        saveConfigs(configs)

        val trigger = WifiTrigger(ssid)
        trigger.arm(context, triggerCallback)
        activeTriggers.add(trigger)
        Log.d(TAG, "Added WiFi trigger: $ssid")
    }

    fun addScheduleTrigger(startH: Int, startM: Int, endH: Int, endM: Int, days: Set<Int>) {
        val configs = loadConfigs().toMutableList()
        val config = TriggerConfig(
            type = "schedule", name = "%02d:%02d-%02d:%02d".format(startH, startM, endH, endM),
            startHour = startH, startMinute = startM, endHour = endH, endMinute = endM,
            daysOfWeek = days
        )
        configs.add(config)
        saveConfigs(configs)

        val trigger = ScheduleTrigger(startH, startM, endH, endM, days)
        trigger.arm(context, triggerCallback)
        activeTriggers.add(trigger)
        Log.d(TAG, "Added schedule trigger: ${config.name}")
    }

    fun addGeofenceTrigger(lat: Double, lng: Double, radius: Float, label: String) {
        val configs = loadConfigs().toMutableList()
        if (configs.any { it.type == "geofence" && it.latitude == lat && it.longitude == lng }) {
            Log.d(TAG, "Geofence trigger already exists for $label")
            return
        }

        configs.add(TriggerConfig(
            type = "geofence", name = label,
            latitude = lat, longitude = lng, radiusMeters = radius
        ))
        saveConfigs(configs)

        val trigger = GeofenceTrigger(lat, lng, radius, label)
        trigger.arm(context, triggerCallback)
        activeTriggers.add(trigger)
        Log.d(TAG, "Added geofence trigger: $label")
    }

    fun addChargingTrigger() {
        val configs = loadConfigs().toMutableList()
        if (configs.any { it.type == "charging" }) {
            Log.d(TAG, "Charging trigger already exists")
            return
        }

        configs.add(TriggerConfig(type = "charging", name = "Charging"))
        saveConfigs(configs)

        val trigger = ChargingTrigger()
        trigger.arm(context, triggerCallback)
        activeTriggers.add(trigger)
        Log.d(TAG, "Added charging trigger")
    }

    fun addAppTrigger(packageName: String, appLabel: String) {
        val configs = loadConfigs().toMutableList()
        if (configs.any { it.type == "app" && it.address == packageName }) {
            Log.d(TAG, "App trigger already exists for $packageName")
            return
        }

        configs.add(TriggerConfig(type = "app", address = packageName, name = appLabel))
        saveConfigs(configs)

        val trigger = AppRunningTrigger(packageName, appLabel)
        trigger.arm(context, triggerCallback)
        activeTriggers.add(trigger)
        Log.d(TAG, "Added app trigger: $appLabel")
    }

    fun addAndroidAutoTrigger() {
        val configs = loadConfigs().toMutableList()
        if (configs.any { it.type == "android_auto" }) {
            Log.d(TAG, "Android Auto trigger already exists")
            return
        }

        configs.add(TriggerConfig(type = "android_auto", name = "Android Auto"))
        saveConfigs(configs)

        val trigger = AndroidAutoTrigger()
        trigger.arm(context, triggerCallback)
        activeTriggers.add(trigger)
        Log.d(TAG, "Added Android Auto trigger")
    }

    private fun createTrigger(config: TriggerConfig): Trigger? {
        return when (config.type) {
            "bluetooth" -> {
                val addr = config.address ?: return null
                val name = config.name ?: addr
                BluetoothTrigger(addr, name)
            }
            "android_auto" -> AndroidAutoTrigger()
            "wifi" -> {
                val ssid = config.name ?: return null
                WifiTrigger(ssid)
            }
            "schedule" -> {
                val sh = config.startHour ?: return null
                val sm = config.startMinute ?: return null
                val eh = config.endHour ?: return null
                val em = config.endMinute ?: return null
                ScheduleTrigger(sh, sm, eh, em, config.daysOfWeek ?: emptySet())
            }
            "geofence" -> {
                val lat = config.latitude ?: return null
                val lng = config.longitude ?: return null
                val r = config.radiusMeters ?: 500f
                val label = config.name ?: "Location"
                GeofenceTrigger(lat, lng, r, label)
            }
            "charging" -> ChargingTrigger()
            "app" -> {
                val pkg = config.address ?: return null
                val label = config.name ?: pkg
                AppRunningTrigger(pkg, label)
            }
            "manual" -> ManualTrigger()
            else -> null
        }
    }

    private fun createTriggerId(config: TriggerConfig): String {
        return when (config.type) {
            "bluetooth" -> "bluetooth_${config.address}"
            "android_auto" -> "android_auto"
            "wifi" -> "wifi_${config.name}"
            "schedule" -> "schedule_${config.startHour}${config.startMinute}_${config.endHour}${config.endMinute}"
            "geofence" -> "geofence_${config.latitude}_${config.longitude}"
            "charging" -> "charging"
            "app" -> "app_${config.address}"
            "manual" -> "manual"
            else -> "unknown"
        }
    }

    private fun loadConfigs(): List<TriggerConfig> {
        val json = prefs.getString(KEY_TRIGGERS, "[]") ?: "[]"
        val type = object : TypeToken<List<TriggerConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveConfigs(configs: List<TriggerConfig>) {
        prefs.edit().putString(KEY_TRIGGERS, gson.toJson(configs)).apply()
    }
}

data class TriggerConfig(
    val type: String,
    val address: String? = null,
    val name: String? = null,
    val startHour: Int? = null,
    val startMinute: Int? = null,
    val endHour: Int? = null,
    val endMinute: Int? = null,
    val daysOfWeek: Set<Int>? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Float? = null,
)
