package io.github.thevellichor.samsungopenring.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import io.github.thevellichor.samsungopenring.app.triggers.TriggerManager
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "openring_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var webhookInput: TextInputEditText
    private lateinit var palgateSessionToken: TextInputEditText
    private lateinit var palgateUserId: TextInputEditText
    private lateinit var palgateDeviceId: TextInputEditText
    private lateinit var triggersList: LinearLayout
    private lateinit var triggersEmpty: TextView
    private lateinit var eventLog: TextView
    private lateinit var triggerManager: TriggerManager
    private var logExpanded = false

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            val current = eventLog.text.toString()
            eventLog.text = if (current == "No events yet.") line else "$current\n$line"
            if (logExpanded) {
                val sv = findViewById<ScrollView>(R.id.mainScroll)
                sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        triggerManager = TriggerManager(this)

        statusText = findViewById(R.id.statusText)
        webhookInput = findViewById(R.id.webhookUrlInput)
        triggersList = findViewById(R.id.triggersList)
        triggersEmpty = findViewById(R.id.triggersEmpty)
        eventLog = findViewById(R.id.eventLog)

        webhookInput.setText(prefs.getString(KEY_WEBHOOK_URL, ""))
        eventLog.text = EventLog.getRecentLines(this)

        // --- Palgate ---
        palgateSessionToken = findViewById(R.id.palgateSessionToken)
        palgateUserId = findViewById(R.id.palgateUserId)
        palgateDeviceId = findViewById(R.id.palgateDeviceId)

        val (savedToken, savedUserId, savedDeviceId) = PalgateGateOpener.getConfig(this)
        palgateSessionToken.setText(savedToken)
        palgateUserId.setText(savedUserId)
        palgateDeviceId.setText(savedDeviceId)

        findViewById<MaterialButton>(R.id.palgateTestButton).setOnClickListener {
            savePalgateConfig()
            statusText.text = "Opening gate..."
            PalgateGateOpener.openGate(this) { success, detail ->
                runOnUiThread {
                    statusText.text = if (success) "Gate opened ($detail)" else "Gate failed: $detail"
                }
            }
        }

        // --- Manual Control ---
        findViewById<MaterialButton>(R.id.startButton).setOnClickListener {
            ensureBluetooth {
                saveWebhookUrl()
                savePalgateConfig()
                GestureService.start(this)
                statusText.text = "Starting..."
            }
        }
        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            GestureService.stop(this)
            statusText.text = "Stopped"
        }

        // --- Triggers ---
        findViewById<MaterialButton>(R.id.addTriggerButton).setOnClickListener {
            showTriggerTypePicker()
        }
        findViewById<MaterialButton>(R.id.armTriggersButton).setOnClickListener {
            saveWebhookUrl()
            triggerManager.armAll()
            EventLog.log(this, "Triggers armed")
            statusText.text = "Triggers armed"
        }
        findViewById<MaterialButton>(R.id.disarmTriggersButton).setOnClickListener {
            triggerManager.disarmAll()
            GestureService.stop(this)
            EventLog.log(this, "Triggers disarmed")
            statusText.text = "Disarmed"
        }
        // --- Advanced ---
        findViewById<TextView>(R.id.readLogsStatus).text = ShizukuHelper.getStatusText(this)
        findViewById<MaterialButton>(R.id.shizukuGrantButton).setOnClickListener {
            doShizukuGrant()
        }
        findViewById<MaterialButton>(R.id.copyAdbButton).setOnClickListener {
            ShizukuHelper.copyAdbCommandToClipboard(this)
        }

        // --- Log toggle ---
        findViewById<ImageButton>(R.id.toggleLogButton).setOnClickListener {
            logExpanded = !logExpanded
            eventLog.visibility = if (logExpanded) View.VISIBLE else View.GONE
            (it as ImageButton).setImageResource(
                if (logExpanded) android.R.drawable.arrow_up_float
                else android.R.drawable.arrow_down_float
            )
        }
        findViewById<MaterialButton>(R.id.exportLogButton).setOnClickListener { exportLog() }
        findViewById<MaterialButton>(R.id.clearLogButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear log?")
                .setPositiveButton("Clear") { _, _ ->
                    EventLog.clear(this)
                    eventLog.text = "No events yet."
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // --- Help buttons ---
        setupHelp()

        refreshTriggerList()
    }

    override fun onResume() {
        super.onResume()
        EventLog.addListener(logListener)
        eventLog.text = EventLog.getRecentLines(this)
        findViewById<TextView>(R.id.readLogsStatus).text = ShizukuHelper.getStatusText(this)
    }

    override fun onPause() {
        super.onPause()
        EventLog.removeListener(logListener)
    }

    // --- Help dialogs ---

    private fun setupHelp() {
        findViewById<ImageButton>(R.id.helpManual).setOnClickListener {
            showHelp("Manual Control",
                "Start/Stop gesture monitoring immediately.\n\n" +
                "When started, the app connects to your Galaxy Ring over BLE, " +
                "enables gesture detection, and fires your webhook on every double-pinch.\n\n" +
                "Use this for quick testing. For automatic activation, use Triggers instead.")
        }
        findViewById<ImageButton>(R.id.helpWebhook).setOnClickListener {
            showHelp("Webhook",
                "The URL that receives an HTTP POST when a gesture is detected.\n\n" +
                "The POST body is JSON:\n" +
                "{\n  \"gesture\": \"double_pinch\",\n  \"gesture_id\": 42,\n  \"timestamp\": \"...\",\n  \"device\": \"SamsungOpenRing\"\n}\n\n" +
                "Use webhook.site for testing, or point to your own server, Home Assistant, IFTTT, etc.")
        }
        findViewById<ImageButton>(R.id.helpTriggers).setOnClickListener {
            showHelp("Triggers",
                "Triggers automatically enable gesture monitoring when a condition is met, " +
                "and disable it when the condition ends. This saves ring battery.\n\n" +
                "Example: add a Bluetooth trigger for your car stereo. When your phone " +
                "connects to the car, gestures activate. When you disconnect, they stop.\n\n" +
                "Tap 'Add Trigger' to configure. Long-press the trigger list to remove.\n" +
                "Tap 'Arm' to start watching for trigger conditions.")
        }
        findViewById<ImageButton>(R.id.helpAdvanced).setOnClickListener {
            showHelp("Advanced",
                "READ_LOGS permission is optional. It enables a fallback logcat monitoring " +
                "method — not needed for the primary BLE gesture detection.\n\n" +
                "Shizuku: Grants the permission without a computer. Requires the free " +
                "Shizuku app from Play Store.\n\n" +
                "ADB: Run the copied command from a computer once. Permanent, survives reboots.")
        }
    }

    private fun showHelp(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Got it", null)
            .show()
    }

    // --- Trigger type picker (replaces 7 individual buttons) ---

    private fun showTriggerTypePicker() {
        val types = arrayOf(
            "Bluetooth device",
            "WiFi network",
            "Android Auto",
            "Time schedule",
            "Location (geofence)",
            "Charging",
            "App in foreground"
        )

        AlertDialog.Builder(this)
            .setTitle("Add trigger")
            .setItems(types) { _, which ->
                when (which) {
                    0 -> ensureBluetooth { showBluetoothDevicePicker() }
                    1 -> showWifiNameDialog()
                    2 -> { triggerManager.addAndroidAutoTrigger(); refreshTriggerList(); EventLog.log(this, "Added Android Auto trigger") }
                    3 -> showScheduleDialog()
                    4 -> ensureLocation { showGeofenceDialog() }
                    5 -> { triggerManager.addChargingTrigger(); refreshTriggerList(); EventLog.log(this, "Added charging trigger") }
                    6 -> showAppPicker()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Permission helpers ---

    private fun ensureBluetooth(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingAction = action
            requestPermissions(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS,
            ), 1)
        }
    }

    private fun ensureLocation(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingAction = action
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
        }
    }

    private var pendingAction: (() -> Unit)? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingAction?.invoke()
        } else {
            statusText.text = "Permission denied"
        }
        pendingAction = null
    }

    // --- Trigger dialogs ---

    private fun showBluetoothDevicePicker() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val devices = adapter.bondedDevices?.toList() ?: return
        if (devices.isEmpty()) { statusText.text = "No paired devices"; return }

        val names = devices.map { "${it.name ?: "?"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select device")
            .setItems(names) { _, i ->
                val d = devices[i]
                triggerManager.addBluetoothTrigger(d.address, d.name ?: d.address)
                refreshTriggerList()
                EventLog.log(this, "Added BT trigger: ${d.name}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWifiNameDialog() {
        val input = EditText(this).apply { hint = "Network name (SSID)"; setPadding(48, 32, 48, 16) }
        AlertDialog.Builder(this)
            .setTitle("WiFi trigger")
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
            orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 16)
        }
        val startInput = EditText(this).apply { hint = "Start (HH:MM)" }
        val endInput = EditText(this).apply { hint = "End (HH:MM)" }
        val daysInput = EditText(this).apply { hint = "Days (Mon,Tue,... or empty=all)" }
        layout.addView(startInput); layout.addView(endInput); layout.addView(daysInput)

        AlertDialog.Builder(this)
            .setTitle("Schedule trigger")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val s = parseTime(startInput.text.toString())
                val e = parseTime(endInput.text.toString())
                if (s != null && e != null) {
                    triggerManager.addScheduleTrigger(s.first, s.second, e.first, e.second, parseDays(daysInput.text.toString()))
                    refreshTriggerList()
                    EventLog.log(this, "Added schedule trigger")
                } else statusText.text = "Invalid time (use HH:MM)"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGeofenceDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 16)
        }
        val labelInput = EditText(this).apply { hint = "Label (e.g. Home)" }
        val latInput = EditText(this).apply {
            hint = "Latitude"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val lngInput = EditText(this).apply {
            hint = "Longitude"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val radiusInput = EditText(this).apply {
            hint = "Radius meters (default 500)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(labelInput); layout.addView(latInput); layout.addView(lngInput); layout.addView(radiusInput)

        AlertDialog.Builder(this)
            .setTitle("Location trigger")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val lat = latInput.text.toString().toDoubleOrNull()
                val lng = lngInput.text.toString().toDoubleOrNull()
                if (lat != null && lng != null) {
                    val r = radiusInput.text.toString().toFloatOrNull() ?: 500f
                    val label = labelInput.text.toString().trim().ifEmpty { "Location" }
                    triggerManager.addGeofenceTrigger(lat, lng, r, label)
                    refreshTriggerList()
                    EventLog.log(this, "Added location trigger: $label")
                } else statusText.text = "Invalid coordinates"
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
            .setItems(labels) { _, i ->
                val label = pm.getApplicationLabel(apps[i]).toString()
                triggerManager.addAppTrigger(apps[i].packageName, label)
                refreshTriggerList()
                EventLog.log(this, "Added app trigger: $label")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Shizuku ---

    private fun doShizukuGrant() {
        val status = findViewById<TextView>(R.id.readLogsStatus)
        if (ShizukuHelper.hasReadLogs(this)) { status.text = "READ_LOGS already granted!"; return }
        if (!ShizukuHelper.isShizukuInstalled(this)) { status.text = "Install Shizuku from Play Store"; return }
        if (!ShizukuHelper.isShizukuRunning()) { status.text = "Open Shizuku app and start it"; return }
        if (!ShizukuHelper.hasShizukuPermission()) {
            ShizukuHelper.requestPermission { granted ->
                runOnUiThread { if (granted) doShizukuGrantInner(status) else status.text = "Permission denied" }
            }
            return
        }
        doShizukuGrantInner(status)
    }

    private fun doShizukuGrantInner(statusView: TextView) {
        statusView.text = "Granting..."
        ShizukuHelper.bindAndGrant(packageName) { success, message ->
            runOnUiThread {
                statusView.text = if (success) "READ_LOGS granted!" else "Failed: $message"
                EventLog.log(this, "Shizuku: $message")
            }
        }
    }

    // --- Helpers ---

    private fun refreshTriggerList() {
        val configs = triggerManager.getConfiguredTriggers()
        triggersList.removeAllViews()

        if (configs.isEmpty()) {
            triggersEmpty.visibility = View.VISIBLE
            return
        }
        triggersEmpty.visibility = View.GONE

        for (config in configs) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_trigger, triggersList, false)

            val label = row.findViewById<TextView>(R.id.triggerLabel)
            label.text = when (config.type) {
                "bluetooth" -> "BT: ${config.name}"
                "android_auto" -> "Android Auto"
                "wifi" -> "WiFi: ${config.name}"
                "schedule" -> "Schedule: ${config.name}"
                "geofence" -> "Location: ${config.name} (${config.radiusMeters?.toInt() ?: 500}m)"
                "charging" -> "Charging"
                "app" -> "App: ${config.name}"
                else -> config.type
            }

            val triggerId = when (config.type) {
                "bluetooth" -> "bluetooth_${config.address}"
                "android_auto" -> "android_auto"
                "wifi" -> "wifi_${config.name}"
                "schedule" -> "schedule_${config.startHour}${config.startMinute}_${config.endHour}${config.endMinute}"
                "geofence" -> "geofence_${config.latitude}_${config.longitude}"
                "charging" -> "charging"
                "app" -> "app_${config.address}"
                else -> ""
            }

            row.findViewById<ImageButton>(R.id.triggerEditButton).setOnClickListener {
                showEditTriggerDialog(config, triggerId)
            }

            row.findViewById<ImageButton>(R.id.triggerDeleteButton).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Remove trigger?")
                    .setMessage(label.text)
                    .setPositiveButton("Remove") { _, _ ->
                        triggerManager.removeTrigger(triggerId)
                        refreshTriggerList()
                        EventLog.log(this, "Removed: ${label.text}")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            triggersList.addView(row)
        }
    }

    private fun showEditTriggerDialog(config: io.github.thevellichor.samsungopenring.app.triggers.TriggerConfig, triggerId: String) {
        // Remove old and re-add with new config via the appropriate dialog
        when (config.type) {
            "bluetooth" -> {
                triggerManager.removeTrigger(triggerId)
                ensureBluetooth { showBluetoothDevicePicker() }
            }
            "wifi" -> {
                triggerManager.removeTrigger(triggerId)
                showWifiNameDialog()
            }
            "schedule" -> {
                triggerManager.removeTrigger(triggerId)
                showScheduleDialog()
            }
            "geofence" -> {
                triggerManager.removeTrigger(triggerId)
                ensureLocation { showGeofenceDialog() }
            }
            "app" -> {
                triggerManager.removeTrigger(triggerId)
                showAppPicker()
            }
            else -> {
                // Android Auto, Charging — nothing to edit, just inform
                AlertDialog.Builder(this)
                    .setTitle("Edit")
                    .setMessage("This trigger has no configurable options.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun saveWebhookUrl() {
        prefs.edit().putString(KEY_WEBHOOK_URL, webhookInput.text.toString().trim()).apply()
    }

    private fun savePalgateConfig() {
        PalgateGateOpener.saveConfig(
            this,
            palgateSessionToken.text.toString().trim(),
            palgateUserId.text.toString().trim(),
            palgateDeviceId.text.toString().trim()
        )
    }

    private fun exportLog() {
        val log = EventLog.getFullLog(this)
        if (log.isBlank()) { statusText.text = "No log to export"; return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, log)
            putExtra(Intent.EXTRA_SUBJECT, "SamsungOpenRing Event Log")
        }, "Export log"))
    }

    private fun parseTime(text: String): Pair<Int, Int>? {
        val p = text.trim().split(":"); if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null; val m = p[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    private fun parseDays(text: String): Set<Int> {
        if (text.isBlank()) return emptySet()
        val map = mapOf("mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY)
        return text.lowercase().split(",", " ").mapNotNull { map[it.trim()] }.toSet()
    }
}
