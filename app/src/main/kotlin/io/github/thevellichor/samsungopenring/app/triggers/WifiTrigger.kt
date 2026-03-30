package io.github.thevellichor.samsungopenring.app.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log

class WifiTrigger(
    private val targetSsid: String,
) : Trigger {

    companion object {
        private const val TAG = "OpenRing.WifiTrigger"
    }

    override val id = "wifi_$targetSsid"
    override val name = "WiFi: $targetSsid"
    override val description = "Activate when connected to $targetSsid"

    private var armed = false
    private var callback: TriggerCallback? = null
    private var active = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun arm(context: Context, callback: TriggerCallback) {
        if (armed) return
        this.callback = callback

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val wifiInfo = capabilities.transportInfo as? WifiInfo ?: return
                // SSID comes quoted on some Android versions
                val ssid = wifiInfo.ssid?.removeSurrounding("\"") ?: return

                if (ssid.equals(targetSsid, ignoreCase = true)) {
                    if (!active) {
                        Log.d(TAG, "Connected to target WiFi: $ssid")
                        active = true
                        callback.onActivated(this@WifiTrigger)
                    }
                }
            }

            override fun onLost(network: Network) {
                if (!active) return
                // Re-check current SSID — onLost fires for ANY wifi, not just ours
                val currentSsid = getCurrentSsid(context)
                if (currentSsid == null || !currentSsid.equals(targetSsid, ignoreCase = true)) {
                    Log.d(TAG, "Target WiFi lost (current: $currentSsid)")
                    active = false
                    callback.onDeactivated(this@WifiTrigger)
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback!!)

        // Check if already connected to target
        val currentSsid = getCurrentSsid(context)
        if (currentSsid != null && currentSsid.equals(targetSsid, ignoreCase = true)) {
            Log.d(TAG, "Already connected to $targetSsid")
            active = true
            callback.onActivated(this)
        }

        armed = true
        Log.d(TAG, "Armed for SSID: $targetSsid")
    }

    override fun disarm(context: Context) {
        if (!armed) return
        networkCallback?.let {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
        networkCallback = null
        callback = null
        armed = false
        active = false
        Log.d(TAG, "Disarmed")
    }

    override fun isArmed() = armed

    private fun getCurrentSsid(context: Context): String? {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val info = wifiManager?.connectionInfo
        return info?.ssid?.removeSurrounding("\"")
    }
}
