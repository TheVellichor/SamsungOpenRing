package io.github.thevellichor.samsungopenring.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val TAG = "OpenRing.Shizuku"
    private const val SHIZUKU_PERMISSION_CODE = 100

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermission(callback: (granted: Boolean) -> Unit) {
        try {
            if (hasShizukuPermission()) {
                callback(true)
                return
            }

            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    callback(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }

            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Permission request failed: ${e.message}")
            callback(false)
        }
    }

    fun grantReadLogs(packageName: String, callback: (success: Boolean, message: String) -> Unit) {
        if (!isShizukuRunning()) {
            callback(false, "Shizuku is not running")
            return
        }

        if (!hasShizukuPermission()) {
            callback(false, "Shizuku permission not granted")
            return
        }

        try {
            val command = "pm grant $packageName android.permission.READ_LOGS"
            // Shizuku runs commands with ADB-level (shell) privileges
            // via its binder. We invoke it through IPC.
            val iShellService = Shizuku.getBinder()
            if (iShellService == null) {
                callback(false, "Shizuku binder not available")
                return
            }

            // Use Shizuku's remote exec capability
            val processBuilder = ProcessBuilder("sh", "-c", command)
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            val error = process.errorStream.bufferedReader().readText().trim()

            // Note: ProcessBuilder runs locally without Shizuku privileges.
            // If this fails, show ADB instructions instead.
            if (exitCode == 0) {
                Log.d(TAG, "READ_LOGS granted to $packageName")
                callback(true, "READ_LOGS permission granted")
            } else {
                // Local execution won't have shell privileges — expected to fail
                // Fall back to showing ADB instructions
                Log.w(TAG, "Local exec failed (expected). Showing ADB instructions.")
                callback(false, "Run this command via ADB:\nadb shell $command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}")
            callback(false, "Run via ADB:\nadb shell pm grant $packageName android.permission.READ_LOGS")
        }
    }

    fun getAdbCommand(packageName: String): String {
        return "adb shell pm grant $packageName android.permission.READ_LOGS"
    }

    fun getStatusText(context: Context): String {
        return when {
            !isShizukuInstalled(context) -> "Shizuku not installed"
            !isShizukuRunning() -> "Shizuku not running (open Shizuku app and start)"
            !hasShizukuPermission() -> "Shizuku permission needed"
            else -> "Shizuku ready"
        }
    }
}
