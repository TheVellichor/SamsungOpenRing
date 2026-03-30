package io.github.thevellichor.samsungopenring.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import io.github.thevellichor.samsungopenring.app.triggers.TriggerManager
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "openring_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var webhookInput: EditText
    private lateinit var triggersInfo: TextView
    private lateinit var eventLog: TextView
    private lateinit var triggerManager: TriggerManager

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            val current = eventLog.text.toString()
            eventLog.text = if (current == "No events yet.") {
                line
            } else {
                "$current\n$line"
            }
            // Auto-scroll to bottom
            val scrollView = eventLog.parent as? ScrollView
                ?: eventLog.parent?.parent as? ScrollView
            scrollView?.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        triggerManager = TriggerManager(this)

        statusText = findViewById(R.id.statusText)
        webhookInput = findViewById(R.id.webhookUrlInput)
        triggersInfo = findViewById(R.id.triggersInfo)
        eventLog = findViewById(R.id.eventLog)

        webhookInput.setText(prefs.getString(KEY_WEBHOOK_URL, ""))

        // Load existing log
        eventLog.text = EventLog.getRecentLines(this)

        findViewById<MaterialButton>(R.id.addBluetoothTriggerButton).setOnClickListener {
            if (!hasBluetoothPermission()) {
                requestPermissions(arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS,
                ), 2)
                return@setOnClickListener
            }
            showBluetoothDevicePicker()
        }

        findViewById<MaterialButton>(R.id.addWifiTriggerButton).setOnClickListener {
            showWifiNameDialog()
        }

        findViewById<MaterialButton>(R.id.addAndroidAutoTriggerButton).setOnClickListener {
            triggerManager.addAndroidAutoTrigger()
            refreshTriggerList()
            EventLog.log(this, "Added Android Auto trigger")
        }

        findViewById<MaterialButton>(R.id.addScheduleTriggerButton).setOnClickListener {
            showScheduleDialog()
        }

        findViewById<MaterialButton>(R.id.addGeofenceTriggerButton).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 3)
                return@setOnClickListener
            }
            showGeofenceDialog()
        }

        findViewById<MaterialButton>(R.id.addChargingTriggerButton).setOnClickListener {
            triggerManager.addChargingTrigger()
            refreshTriggerList()
            EventLog.log(this, "Added charging trigger")
        }

        findViewById<MaterialButton>(R.id.addAppTriggerButton).setOnClickListener {
            showAppPicker()
        }

        findViewById<MaterialButton>(R.id.armTriggersButton).setOnClickListener {
            saveWebhookUrl()
            triggerManager.armAll()
            EventLog.log(this, "All triggers armed")
            statusText.text = "Status: Triggers armed"
        }

        findViewById<MaterialButton>(R.id.disarmTriggersButton).setOnClickListener {
            triggerManager.disarmAll()
            GestureService.stop(this)
            EventLog.log(this, "All triggers disarmed")
            statusText.text = "Status: Disarmed"
        }

        findViewById<MaterialButton>(R.id.startButton).setOnClickListener {
            if (!hasBluetoothPermission()) {
                requestPermissions(arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS,
                ), 1)
                return@setOnClickListener
            }
            saveWebhookUrl()
            GestureService.start(this)
            statusText.text = "Status: Starting..."
        }

        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            GestureService.stop(this)
            statusText.text = "Status: Stopped"
        }

        findViewById<MaterialButton>(R.id.exportLogButton).setOnClickListener {
            exportLog()
        }

        findViewById<MaterialButton>(R.id.clearLogButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear log?")
                .setMessage("This will delete all recorded events.")
                .setPositiveButton("Clear") { _, _ ->
                    EventLog.clear(this)
                    eventLog.text = "No events yet."
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refreshTriggerList()
    }

    override fun onResume() {
        super.onResume()
        EventLog.addListener(logListener)
        eventLog.text = EventLog.getRecentLines(this)
    }

    override fun onPause() {
        super.onPause()
        EventLog.removeListener(logListener)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            when (requestCode) {
                1 -> {
                    saveWebhookUrl()
                    GestureService.start(this)
                    statusText.text = "Status: Starting..."
                }
                2 -> showBluetoothDevicePicker()
                3 -> showGeofenceDialog()
            }
        } else {
            statusText.text = "Status: Permission denied"
        }
    }

    private fun exportLog() {
        val logFile = EventLog.getLogFile(this)
        if (!logFile.exists() || logFile.length() == 0L) {
            statusText.text = "Status: No log to export"
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, EventLog.getFullLog(this@MainActivity))
            putExtra(Intent.EXTRA_SUBJECT, "SamsungOpenRing Event Log")
        }
        startActivity(Intent.createChooser(intent, "Export log"))
    }

    private fun showBluetoothDevicePicker() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            statusText.text = "Status: No Bluetooth adapter"
            return
        }

        val devices = adapter.bondedDevices?.toList() ?: emptyList()
        if (devices.isEmpty()) {
            statusText.text = "Status: No paired Bluetooth devices"
            return
        }

        val names = devices.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth device")
            .setItems(names) { _, which ->
                val device = devices[which]
                triggerManager.addBluetoothTrigger(device.address, device.name ?: device.address)
                refreshTriggerList()
                EventLog.log(this, "Added BT trigger: ${device.name}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWifiNameDialog() {
        val input = EditText(this).apply {
            hint = "WiFi network name (SSID)"
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("Add WiFi Trigger")
            .setMessage("Enter the WiFi network name:")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val ssid = input.text.toString().trim()
                if (ssid.isNotEmpty()) {
                    triggerManager.addWifiTrigger(ssid)
                    refreshTriggerList()
                    EventLog.log(this, "Added WiFi trigger: $ssid")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showScheduleDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val startInput = EditText(this).apply { hint = "Start time (HH:MM, e.g. 09:00)" }
        val endInput = EditText(this).apply { hint = "End time (HH:MM, e.g. 18:00)" }
        val daysInput = EditText(this).apply { hint = "Days (e.g. Mon,Tue,Wed or empty for all)" }

        layout.addView(startInput)
        layout.addView(endInput)
        layout.addView(daysInput)

        AlertDialog.Builder(this)
            .setTitle("Add Schedule Trigger")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val start = parseTime(startInput.text.toString())
                val end = parseTime(endInput.text.toString())
                if (start != null && end != null) {
                    val days = parseDays(daysInput.text.toString())
                    triggerManager.addScheduleTrigger(start.first, start.second, end.first, end.second, days)
                    refreshTriggerList()
                    EventLog.log(this, "Added schedule trigger: ${startInput.text}-${endInput.text}")
                } else {
                    statusText.text = "Status: Invalid time format (use HH:MM)"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGeofenceDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val labelInput = EditText(this).apply { hint = "Label (e.g. Home, Office)" }
        val latInput = EditText(this).apply {
            hint = "Latitude (e.g. 32.0853)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val lngInput = EditText(this).apply {
            hint = "Longitude (e.g. 34.7818)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val radiusInput = EditText(this).apply {
            hint = "Radius in meters (default 500)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(labelInput)
        layout.addView(latInput)
        layout.addView(lngInput)
        layout.addView(radiusInput)

        AlertDialog.Builder(this)
            .setTitle("Add Location Trigger")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val label = labelInput.text.toString().trim().ifEmpty { "Location" }
                val lat = latInput.text.toString().toDoubleOrNull()
                val lng = lngInput.text.toString().toDoubleOrNull()
                val radius = radiusInput.text.toString().toFloatOrNull() ?: 500f

                if (lat != null && lng != null) {
                    triggerManager.addGeofenceTrigger(lat, lng, radius, label)
                    refreshTriggerList()
                    EventLog.log(this, "Added location trigger: $label")
                } else {
                    statusText.text = "Status: Invalid coordinates"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppPicker() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val labels = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select app")
            .setItems(labels) { _, which ->
                val app = apps[which]
                val label = pm.getApplicationLabel(app).toString()
                triggerManager.addAppTrigger(app.packageName, label)
                refreshTriggerList()
                EventLog.log(this, "Added app trigger: $label")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshTriggerList() {
        val configs = triggerManager.getConfiguredTriggers()
        if (configs.isEmpty()) {
            triggersInfo.text = "No triggers configured."
        } else {
            triggersInfo.text = configs.joinToString("\n") { config ->
                when (config.type) {
                    "bluetooth" -> "BT: ${config.name} (${config.address})"
                    "android_auto" -> "Android Auto"
                    "wifi" -> "WiFi: ${config.name}"
                    "schedule" -> "Schedule: ${config.name}"
                    "geofence" -> "Location: ${config.name} (${config.radiusMeters?.toInt() ?: 500}m)"
                    "charging" -> "Charging"
                    "app" -> "App: ${config.name}"
                    "manual" -> "Manual toggle"
                    else -> config.type
                }
            }
            // Long-press hint
            triggersInfo.append("\n\n(Long-press a trigger name above to remove)")
        }

        triggersInfo.setOnLongClickListener {
            showRemoveTriggerDialog()
            true
        }
    }

    private fun showRemoveTriggerDialog() {
        val configs = triggerManager.getConfiguredTriggers()
        if (configs.isEmpty()) return

        val labels = configs.map { config ->
            when (config.type) {
                "bluetooth" -> "BT: ${config.name}"
                "android_auto" -> "Android Auto"
                "wifi" -> "WiFi: ${config.name}"
                "schedule" -> "Schedule: ${config.name}"
                "geofence" -> "Location: ${config.name}"
                "charging" -> "Charging"
                "app" -> "App: ${config.name}"
                else -> config.type
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Remove trigger")
            .setItems(labels) { _, which ->
                val config = configs[which]
                val triggerId = when (config.type) {
                    "bluetooth" -> "bluetooth_${config.address}"
                    "android_auto" -> "android_auto"
                    "wifi" -> "wifi_${config.name}"
                    "schedule" -> "schedule_${config.startHour}${config.startMinute}_${config.endHour}${config.endMinute}"
                    "geofence" -> "geofence_${config.latitude}_${config.longitude}"
                    "charging" -> "charging"
                    "app" -> "app_${config.address}"
                    else -> return@setItems
                }
                triggerManager.removeTrigger(triggerId)
                refreshTriggerList()
                EventLog.log(this, "Removed trigger: ${labels[which]}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveWebhookUrl() {
        val url = webhookInput.text.toString().trim()
        prefs.edit().putString(KEY_WEBHOOK_URL, url).apply()
    }

    private fun hasBluetoothPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun parseTime(text: String): Pair<Int, Int>? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    private fun parseDays(text: String): Set<Int> {
        if (text.isBlank()) return emptySet()
        val map = mapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
            "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY,
            "sun" to Calendar.SUNDAY
        )
        return text.lowercase().split(",", " ").mapNotNull { map[it.trim()] }.toSet()
    }
}
