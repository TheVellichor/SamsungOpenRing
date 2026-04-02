package io.github.thevellichor.samsungopenring.app

import android.content.Context
import android.util.Log
import okhttp3.*
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Opens a PalGate parking gate via the cloud API.
 *
 * Token algorithm reverse-engineered from libnative-lib.so (getFacialLandmarks).
 * AES-128-ECB with custom key derivation from userId + stored session token.
 */
object PalgateGateOpener {

    private const val TAG = "OpenRing.Palgate"
    private const val PREFS_NAME = "openring_prefs"
    private const val KEY_SESSION_TOKEN = "palgate_session_token"
    private const val KEY_USER_ID = "palgate_user_id"
    private const val KEY_DEVICE_ID = "palgate_device_id"
    private const val BASE_URL = "https://api1.pal-es.com/v1/bt/device/"

    private val client = OkHttpClient()
    private val random = SecureRandom()

    // Hardcoded key base from libnative-lib.so
    private val TC_KEY_BASE = byteArrayOf(
        0xFA.toByte(), 0xD3.toByte(), 0x25, 0x72, 0x81.toByte(), 0x29,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // bytes 6-11: filled with user_id
        0x3A, 0xB4.toByte(), 0x5A, 0x65
    )

    fun isConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SESSION_TOKEN, "")!!.isNotBlank()
                && prefs.getString(KEY_USER_ID, "")!!.isNotBlank()
                && prefs.getString(KEY_DEVICE_ID, "")!!.isNotBlank()
    }

    fun getConfig(context: Context): Triple<String, String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Triple(
            prefs.getString(KEY_SESSION_TOKEN, "") ?: "",
            prefs.getString(KEY_USER_ID, "") ?: "",
            prefs.getString(KEY_DEVICE_ID, "") ?: ""
        )
    }

    fun saveConfig(context: Context, sessionToken: String, userId: String, deviceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SESSION_TOKEN, sessionToken)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    fun openGate(
        context: Context,
        callback: ((success: Boolean, detail: String) -> Unit)? = null
    ) {
        val (sessionToken, userIdStr, deviceId) = getConfig(context)
        if (sessionToken.isBlank() || userIdStr.isBlank() || deviceId.isBlank()) {
            Log.w(TAG, "Palgate not configured")
            callback?.invoke(false, "not configured")
            return
        }

        val userId = userIdStr.toLongOrNull() ?: run {
            callback?.invoke(false, "invalid user ID")
            return
        }

        val timestamp = System.currentTimeMillis() / 1000
        val token = generateToken(sessionToken, userId, timestamp, 0)
        val rn = randomNonce(8)

        val url = "${BASE_URL}${deviceId}/open-gate?openBy=100&outputNum=1&rn=$rn"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-bt-token", token)
            .get()
            .build()

        Log.d(TAG, "Opening gate $deviceId...")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Gate open failed: ${e.message}")
                callback?.invoke(false, e.message ?: "network error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string() ?: ""
                    Log.d(TAG, "Gate response: ${response.code} $body")
                    // Server returns status:"ok" even when gate doesn't confirm
                    callback?.invoke(response.isSuccessful, "HTTP ${response.code}")
                }
            }
        })
    }

    private fun generateToken(
        storedTokenHex: String,
        userId: Long,
        timestamp: Long,
        tokenType: Int
    ): String {
        // Step 1: Build AES key with userId embedded at bytes 6-11
        val tcKey = TC_KEY_BASE.copyOf()
        tcKey[6] = ((userId shr 40) and 0xFF).toByte()
        tcKey[7] = ((userId shr 32) and 0xFF).toByte()
        tcKey[8] = ((userId shr 24) and 0xFF).toByte()
        tcKey[9] = ((userId shr 16) and 0xFF).toByte()
        tcKey[10] = ((userId shr 8) and 0xFF).toByte()
        tcKey[11] = (userId and 0xFF).toByte()

        // Step 2: Decrypt stored token with tcKey -> intermediate key
        val storedTokenBytes = hexToBytes(storedTokenHex)
        val intermediateKey = aesDecrypt(storedTokenBytes, tcKey)

        // Step 3: Build plaintext with timestamp
        val plaintext = ByteArray(16)
        random.nextBytes(plaintext)
        plaintext[1] = 0x0A
        plaintext[2] = 0x0A
        val ts32 = (timestamp and 0xFFFFFFFFL).toInt()
        plaintext[10] = ((ts32 shr 24) and 0xFF).toByte()
        plaintext[11] = ((ts32 shr 16) and 0xFF).toByte()
        plaintext[12] = ((ts32 shr 8) and 0xFF).toByte()
        plaintext[13] = (ts32 and 0xFF).toByte()

        // Step 4: Encrypt plaintext with intermediate key
        val ciphertext = aesEncrypt(plaintext, intermediateKey)

        // Step 5: Assemble token: [marker(1)] [userId(6)] [ciphertext(16)] = 23 bytes
        val marker = when (tokenType) {
            1 -> 0x11.toByte()
            2 -> 0x21.toByte()
            else -> 0x01.toByte()
        }

        val token = ByteArray(23)
        token[0] = marker
        token[1] = ((userId shr 40) and 0xFF).toByte()
        token[2] = ((userId shr 32) and 0xFF).toByte()
        token[3] = ((userId shr 24) and 0xFF).toByte()
        token[4] = ((userId shr 16) and 0xFF).toByte()
        token[5] = ((userId shr 8) and 0xFF).toByte()
        token[6] = (userId and 0xFF).toByte()
        System.arraycopy(ciphertext, 0, token, 7, 16)

        return bytesToHex(token)
    }

    private fun aesDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun randomNonce(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}
