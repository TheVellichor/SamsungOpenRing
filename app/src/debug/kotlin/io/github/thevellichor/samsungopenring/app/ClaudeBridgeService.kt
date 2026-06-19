package io.github.thevellichor.samsungopenring.app

import android.app.*
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import io.github.thevellichor.samsungopenring.core.ConnectionCallback
import io.github.thevellichor.samsungopenring.core.GestureListener
import io.github.thevellichor.samsungopenring.core.OpenRing
import io.github.thevellichor.samsungopenring.core.OpenRingError
import io.github.thevellichor.samsungopenring.core.OpenRingLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque

/**
 * Claude Bridge — a DEBUG-ONLY control surface for driving the Galaxy Ring from a
 * computer over ADB. Lives only in the debug source set so it can never ship in a
 * release build.
 *
 * It runs a tiny HTTP server bound to LOOPBACK ONLY (127.0.0.1). Expose it to the
 * host with `adb forward tcp:8787 tcp:8787` and then drive the ring with curl.
 *
 * The command surface is intentionally UNGUARDED (the user opted into full raw
 * access): /write is a pure passthrough to the ring's TX characteristic, and the
 * destructive ops (reboot/reset/shell/FOTA) are reachable. Destructive sends are
 * logged loudly but NOT blocked. Loopback-only bind is the single security boundary.
 *
 * Start:  adb shell am start-foreground-service -n io.github.thevellichor.samsungopenring.app/.ClaudeBridgeService
 *   (or just open the app — the debug Application auto-starts it on first activity resume)
 * Stop:   curl -XPOST 127.0.0.1:8787/stop   (or adb shell am stopservice -n .../.ClaudeBridgeService)
 */
class ClaudeBridgeService : Service() {

    companion object {
        private const val TAG = "OpenRing.Bridge"
        private const val PORT = 8787
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL_ID = "openring_bridge"
        private const val BUFFER_CAP = 4000
        private const val MAX_BODY = 64 * 1024        // bodies are tiny JSON; reject anything larger
        private const val SOCKET_TIMEOUT_MS = 10_000  // don't let a stalled client wedge the server
    }

    private data class Notif(
        val index: Long,
        val t: Long,
        val char: String,
        val hex: String,
        val channel: String?,
    )

    private data class BatterySample(val t: Long, val level: Int, val charging: Boolean, val src: String)

    private val gson = Gson()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var serverThread: Thread? = null

    private val notifLock = Any()
    private val notifs = ArrayDeque<Notif>()
    private var notifSeq = 0L

    private val batteryLock = Any()
    private val battery = ArrayDeque<BatterySample>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Bridge listening on 127.0.0.1:$PORT"))

        // Route core logging into EventLog so /log can serve the full protocol trace.
        if (OpenRing.logger == null) {
            OpenRing.logger = OpenRingLogger { msg -> EventLog.log(this@ClaudeBridgeService, msg) }
        }

        // Tap every raw RX notification: record it and opportunistically decode battery.
        OpenRing.rawListener = { charShort, value ->
            recordNotif(charShort, value)
            decodeBattery(charShort, value)
        }

        startServer()
        log("Claude Bridge started — http://127.0.0.1:$PORT (loopback only)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        OpenRing.rawListener = null
        log("Claude Bridge stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- HTTP server -------------------------------------------------------

    private fun startServer() {
        running = true
        val t = Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT))
                serverSocket = ss
                log("Listening on 127.0.0.1:$PORT")
                while (running) {
                    val socket = try { ss.accept() } catch (e: Exception) { if (running) log("accept error: ${e.message}"); break }
                    // Catch Throwable so an OOM/Error from one request can't kill the accept loop.
                    try { handle(socket) } catch (e: Throwable) { log("handler error: ${e.message}") }
                }
            } catch (e: Throwable) {
                log("server FAILED to bind :$PORT — ${e.message}")
            }
        }, "claude-bridge-http")
        t.isDaemon = true
        serverThread = t
        t.start()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { respond(sock, 400, mapOf("error" to "bad request")); return }
            val method = parts[0]
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val query = if (rawPath.contains('?')) rawPath.substringAfter('?') else ""

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength < 0 || contentLength > MAX_BODY) {
                respond(sock, 413, mapOf("error" to "body too large (max $MAX_BODY bytes)"))
                return
            }
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read)
            }

            val (code, payload) = route(method, path, query, body)
            respond(sock, code, payload)
        }
    }

    private fun respond(sock: Socket, code: Int, payload: Any) {
        val json = gson.toJson(payload)
        val out = sock.getOutputStream()
        val status = if (code == 200) "200 OK" else "$code ERROR"
        val header = "HTTP/1.0 $status\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\nContent-Length: ${json.toByteArray().size}\r\n\r\n"
        out.write(header.toByteArray())
        out.write(json.toByteArray())
        out.flush()
    }

    // ---- routing -----------------------------------------------------------

    private fun route(method: String, path: String, query: String, body: String): Pair<Int, Any> {
        return try {
            when {
                path == "/" -> 200 to help()
                path == "/status" -> 200 to status()
                path == "/gatt" -> 200 to mapOf("dump" to OpenRing.gattDump())
                path == "/read" -> handleRead(query)
                path == "/battery" -> 200 to batteryReport()
                path == "/events" -> 200 to events(query)
                path == "/log" -> 200 to mapOf("log" to EventLog.getRecentLines(this))

                method == "POST" && path == "/connect" -> { doConnect(); 200 to mapOf("ok" to true, "msg" to "connect requested") }
                method == "POST" && path == "/disconnect" -> { OpenRing.disconnect(); 200 to mapOf("ok" to true) }
                method == "POST" && path == "/gestures/enable" -> { OpenRing.enableGestures(noopGesture); 200 to mapOf("ok" to true, "wrote" to "16 16 00") }
                method == "POST" && path == "/gestures/disable" -> { OpenRing.disableGestures(); 200 to mapOf("ok" to true, "wrote" to "16 16 01") }

                method == "POST" && path == "/write" -> handleWrite(body)
                method == "POST" && path == "/connpriority" -> handleConnPriority(body)
                method == "POST" && path == "/battery/sync" -> { OpenRing.writeRaw(byteArrayOf(0x0b, 0x0b, 0x03)); 200 to mapOf("ok" to true, "wrote" to "0b 0b 03") }
                method == "POST" && path == "/stop" -> { stopSelf(); 200 to mapOf("ok" to true, "msg" to "stopping") }

                else -> 404 to mapOf("error" to "no route", "path" to path, "method" to method)
            }
        } catch (e: Exception) {
            500 to mapOf("error" to (e.message ?: e.toString()))
        }
    }

    private fun help(): Any = mapOf(
        "service" to "Claude Bridge (DEBUG)",
        "bind" to "127.0.0.1:$PORT (loopback only)",
        "routes" to listOf(
            "GET  /status",
            "GET  /gatt",
            "GET  /read?uuid=2a29   (safe GATT read by UUID prefix)",
            "GET  /battery",
            "GET  /events?since=N",
            "GET  /log",
            "POST /connect",
            "POST /disconnect",
            "POST /gestures/enable",
            "POST /gestures/disable",
            "POST /write           body {\"hex\":\"16 16 00\",\"withResponse\":false}  (RAW passthrough, unguarded)",
            "POST /connpriority    body {\"priority\":\"low|balanced|high\"}",
            "POST /battery/sync    (asks ring to push CH11 battery info)",
            "POST /stop"
        ),
        "note" to "RAW write is unguarded: reboot/reset/shell/FOTA payloads are reachable and only logged, not blocked."
    )

    private fun status(): Any = mapOf(
        "connected" to OpenRing.isConnected,
        "port" to PORT,
        "notifBuffered" to synchronized(notifLock) { notifs.size },
        "lastBattery" to (synchronized(batteryLock) { battery.peekLast() }?.let {
            mapOf("level" to it.level, "charging" to it.charging, "ageMs" to (now() - it.t))
        }),
    )

    private fun handleWrite(body: String): Pair<Int, Any> {
        val req = parseBody(body)
        val hex = req["hex"] as? String ?: return 400 to mapOf("error" to "missing 'hex'")
        val withResponse = (req["withResponse"] as? Boolean) ?: false
        val bytes = hexToBytes(hex) ?: return 400 to mapOf("error" to "bad hex: $hex")
        if (isDestructive(bytes)) {
            log("⚠ DESTRUCTIVE RAW WRITE requested [${bytes.joinToString(" ") { "%02x".format(it) }}] — sending (unguarded mode)")
        }
        OpenRing.writeRaw(bytes, withResponse)
        return 200 to mapOf("ok" to true, "wrote" to bytes.joinToString(" ") { "%02x".format(it) }, "withResponse" to withResponse)
    }

    private fun handleRead(query: String): Pair<Int, Any> {
        val uuid = query.split("&").firstOrNull { it.startsWith("uuid=") }?.substringAfter("uuid=")
            ?: return 400 to mapOf("error" to "missing ?uuid=<prefix>")
        if (!OpenRing.readCharacteristic(uuid)) {
            return 404 to mapOf("error" to "char not found or not connected", "uuid" to uuid)
        }
        // GATT read is async; poll the result for up to ~2s.
        var v: ByteArray? = null
        for (i in 0 until 20) {
            v = OpenRing.getRead(uuid)
            if (v != null) break
            Thread.sleep(100)
        }
        return 200 to mapOf(
            "uuid" to uuid,
            "found" to (v != null),
            "hex" to v?.joinToString(" ") { "%02x".format(it) },
            "ascii" to v?.let { bytes -> String(bytes.map { if (it in 32..126) it else '.'.code.toByte() }.toByteArray()) }
        )
    }

    private fun handleConnPriority(body: String): Pair<Int, Any> {
        val req = parseBody(body)
        val p = (req["priority"] as? String)?.lowercase() ?: "low"
        val code = when (p) {
            "low" -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            "balanced" -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            "high" -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
            else -> return 400 to mapOf("error" to "priority must be low|balanced|high")
        }
        val ok = OpenRing.requestConnectionPriority(code)
        return 200 to mapOf("ok" to ok, "priority" to p)
    }

    // ---- notification + battery capture ------------------------------------

    private fun recordNotif(charShort: String, value: ByteArray) {
        val channel = if (value.size >= 2 && value[0] == value[1]) decodeChannel(value[0]) else null
        val hex = value.joinToString(" ") { "%02x".format(it) }
        val t = now()
        // Assign the index and insert atomically so /events can never see a gap.
        synchronized(notifLock) {
            notifs.addLast(Notif(notifSeq++, t, charShort, hex, channel))
            while (notifs.size > BUFFER_CAP) notifs.removeFirst()
        }
    }

    /**
     * Decode CH11 MSG_BATTERY_INFO. The ring replies with header 0x42 (response to
     * MSG_ID 2) — confirmed live on SM-Q509 — and may also send 0x02 (request form),
     * so match both via the low 6 bits. param 5 = level, 6 = charging.
     */
    private fun decodeBattery(charShort: String, value: ByteArray) {
        if (value.size >= 5 && value[0].toInt() == 0x0b && value[1].toInt() == 0x0b && (value[2].toInt() and 0x3f) == 0x02) {
            var level = -1
            var charging = false
            var i = 3
            while (i + 1 < value.size) {
                val id = value[i].toInt() and 0xFF
                val v = value[i + 1].toInt() and 0xFF
                if (id == 5) level = v
                if (id == 6) charging = v != 0
                i += 2
            }
            if (level in 0..100) {
                val s = BatterySample(now(), level, charging, "CH11")
                synchronized(batteryLock) {
                    battery.addLast(s)
                    while (battery.size > 2000) battery.removeFirst()
                }
                log("RING BATTERY level=$level charging=$charging src=CH11 [${value.joinToString(" ") { "%02x".format(it) }}]")
            }
        }
    }

    private fun batteryReport(): Any {
        val samples = synchronized(batteryLock) { battery.toList() }
        val drain = if (samples.size >= 2) {
            val first = samples.first(); val last = samples.last()
            val dtHr = (last.t - first.t) / 3_600_000.0
            if (dtHr > 0.001) ((first.level - last.level) / dtHr) else null
        } else null
        return mapOf(
            "current" to samples.lastOrNull()?.level,
            "charging" to samples.lastOrNull()?.charging,
            "drainPctPerHour" to drain?.let { "%.2f".format(it) },
            "samples" to samples.map { mapOf("t" to it.t, "level" to it.level, "charging" to it.charging) },
            "hint" to "POST /battery/sync to request a fresh reading"
        )
    }

    private fun events(query: String): Any {
        val since = query.split("&").firstOrNull { it.startsWith("since=") }
            ?.substringAfter("since=")?.toLongOrNull() ?: 0L
        // Snapshot list and cursor under one lock so nextSince matches what we return.
        val (list, next) = synchronized(notifLock) {
            Pair(notifs.filter { it.index >= since }.toList(), notifSeq)
        }
        return mapOf(
            "nextSince" to next,
            "count" to list.size,
            "events" to list.map { mapOf("i" to it.index, "t" to it.t, "char" to it.char, "channel" to it.channel, "hex" to it.hex) }
        )
    }

    // ---- helpers -----------------------------------------------------------

    private val noopGesture = GestureListener { event ->
        log("GESTURE via bridge: id=${event.gestureId}")
    }

    private fun doConnect() {
        OpenRing.connect(applicationContext, object : ConnectionCallback {
            override fun onConnected() { log("bridge: BLE connected") }
            override fun onDisconnected() { log("bridge: BLE disconnected") }
            override fun onError(error: OpenRingError) { log("bridge: BLE error ${error.message}") }
        })
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseBody(body: String): Map<String, Any?> {
        if (body.isBlank()) return emptyMap()
        return try { gson.fromJson(body, Map::class.java) as Map<String, Any?> } catch (_: Exception) { emptyMap() }
    }

    private fun hexToBytes(s: String): ByteArray? {
        val clean = s.replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.length % 2 != 0 || clean.isEmpty()) return null
        return try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) { null }
    }

    /** Known dangerous payloads — logged, NOT blocked (user chose unguarded mode). */
    private fun isDestructive(b: ByteArray): Boolean {
        if (b.size < 3) return false
        val c0 = b[0].toInt() and 0xFF; val c1 = b[1].toInt() and 0xFF; val msg = b[2].toInt() and 0xFF
        // CH11 (0x0b) MSG_RESET_RING=33; CH12 (0x0c) REBOOT=40, FACTORY_RESET=41, SHELL=49; CH21 (0x15) FOTA
        return (c0 == c1) && (
            (c0 == 0x0b && msg == 33) ||
            (c0 == 0x0c && (msg == 40 || msg == 41 || msg == 49)) ||
            (c0 == 0x15)
        )
    }

    private fun decodeChannel(b0: Byte): String = when (b0.toInt() and 0xFF) {
        0x0a -> "CH10:Health"; 0x0b -> "CH11:Settings"; 0x0c -> "CH12:Debug"
        0x14 -> "CH20:Logging"; 0x15 -> "CH21:FOTA"; 0x16 -> "CH22:Gesture"
        0x17 -> "CH23:Heartbeat"; 0x1f -> "CH31:FindDevice"; 0x20 -> "CH32:Text"
        else -> "CH${b0.toInt() and 0xFF}"
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun log(message: String) {
        Log.d(TAG, message)
        EventLog.log(this, message)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Claude Bridge (debug)", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Debug control bridge for the Galaxy Ring" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Claude Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
