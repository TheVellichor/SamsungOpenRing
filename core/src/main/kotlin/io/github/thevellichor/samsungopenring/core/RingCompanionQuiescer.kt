package io.github.thevellichor.samsungopenring.core

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Best-effort pause of Samsung Galaxy Wearable / Ring GATT clients before exclusive
 * operations (for example Channel 11 factory reset) from a second BLE client.
 *
 * Normal apps cannot force-stop companions; [ActivityManager.killBackgroundProcesses]
 * is the supported tier. Operators can also force-stop Galaxy Wearable and Ring in
 * system Settings when acknowledgements fail.
 */
class RingCompanionQuiescer(private val context: Context) {

    data class QuiesceResult(
        val killed: List<String>,
        val killDenied: List<String> = emptyList(),
    ) {
        fun logLine(): String = buildString {
            append("killed=${killed.size}")
            if (killDenied.isNotEmpty()) {
                append(" kill_denied=${killDenied.size}")
            }
        }
    }

    /**
     * Stops installed Samsung companion packages that commonly hold Ring GATT sessions.
     */
    fun quiesceOnce(): QuiesceResult {
        val pm = context.packageManager
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val installed = installedCompanionPackages(pm, COMPANION_PACKAGE_CANDIDATES)
        val killed = mutableListOf<String>()
        val killDenied = mutableListOf<String>()
        installed.forEach { packageName ->
            when (tryKillBackgroundProcess(activityManager, packageName)) {
                KillOutcome.KILLED -> killed.add(packageName)
                KillOutcome.DENIED -> killDenied.add(packageName)
            }
        }
        return QuiesceResult(killed = killed, killDenied = killDenied)
    }

    private enum class KillOutcome { KILLED, DENIED }

    companion object {
        /** Packages that often compete for Ring GATT on Samsung phones. */
        val COMPANION_PACKAGE_CANDIDATES: List<String> = listOf(
            "com.samsung.android.ringplugin",
            "com.samsung.android.app.watchmanager",
            "com.samsung.android.wearable.watchmanagerplugin",
            "com.samsung.android.gearoplugin",
            "com.samsung.android.heartplugin",
            "com.samsung.accessory.paranmgr",
            "com.samsung.accessory.budsunitemgr",
        )

        fun installedCompanionPackages(pm: PackageManager, candidates: List<String>): List<String> =
            candidates.filter { packageName ->
                runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess
            }

        fun tryKillBackgroundProcess(activityManager: ActivityManager?, packageName: String): KillOutcome {
            if (activityManager == null) return KillOutcome.DENIED
            return runCatching {
                activityManager.killBackgroundProcesses(packageName)
                KillOutcome.KILLED
            }.getOrDefault(KillOutcome.DENIED)
        }
    }
}
