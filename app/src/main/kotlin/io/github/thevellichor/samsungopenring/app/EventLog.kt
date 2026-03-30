package io.github.thevellichor.samsungopenring.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

object EventLog {

    private const val LOG_FILE = "openring_events.log"
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024 // 2MB
    private const val MAX_DISPLAY_LINES = 200

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun log(context: Context, message: String) {
        val timestamp = formatter.format(Instant.now())
        val line = "[$timestamp] $message"

        // Write to file on background thread
        val file = getLogFile(context)
        Thread {
            try {
                // Rotate if too large
                if (file.exists() && file.length() > MAX_FILE_SIZE) {
                    val backup = File(file.parent, "${file.name}.old")
                    backup.delete()
                    file.renameTo(backup)
                }
                file.appendText(line + "\n")
            } catch (_: Exception) {}
        }.start()

        // Notify listeners on main thread
        mainHandler.post {
            for (listener in listeners) {
                listener(line)
            }
        }
    }

    fun getRecentLines(context: Context, maxLines: Int = MAX_DISPLAY_LINES): String {
        val file = getLogFile(context)
        if (!file.exists() || file.length() == 0L) return "No events yet."

        return try {
            val lines = file.readLines()
            val recent = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
            recent.joinToString("\n").ifEmpty { "No events yet." }
        } catch (_: Exception) {
            "Error reading log."
        }
    }

    fun getFullLog(context: Context): String {
        val file = getLogFile(context)
        if (!file.exists()) return ""
        return try { file.readText() } catch (_: Exception) { "" }
    }

    fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE)
    }

    fun clear(context: Context) {
        try {
            getLogFile(context).delete()
            File(context.filesDir, "$LOG_FILE.old").delete()
        } catch (_: Exception) {}
    }
}
