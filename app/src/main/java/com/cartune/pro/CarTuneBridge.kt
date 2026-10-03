package com.cartune.pro

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JavaScript <-> Native bridge.
 * Call from JS: AndroidBridge.haptic() / AndroidBridge.saveProfile("{...}")
 */
class CarTuneBridge(private val context: Context) {

    /** Trigger short haptic feedback */
    @JavascriptInterface
    fun haptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(30)
                }
            }
        } catch (e: Exception) {
            // Silently ignore — vibration is optional
        }
    }

    /** Save tuning profile to SharedPreferences */
    @JavascriptInterface
    fun saveProfile(name: String, json: String) {
        try {
            val prefs = context.getSharedPreferences("TuneProfiles", Context.MODE_PRIVATE)
            prefs.edit().putString("profile_$name", json).apply()
        } catch (e: Exception) {
            // Ignore
        }
    }

    /** Load tuning profile from SharedPreferences */
    @JavascriptInterface
    fun loadProfile(name: String): String {
        return try {
            val prefs = context.getSharedPreferences("TuneProfiles", Context.MODE_PRIVATE)
            prefs.getString("profile_$name", "{}") ?: "{}"
        } catch (e: Exception) {
            "{}"
        }
    }

    /** Get all saved profile names as JSON array string */
    @JavascriptInterface
    fun listProfiles(): String {
        return try {
            val prefs = context.getSharedPreferences("TuneProfiles", Context.MODE_PRIVATE)
            val names = prefs.all.keys
                .filter { it.startsWith("profile_") }
                .map { "\"${it.removePrefix("profile_")}\"" }
            "[${names.joinToString(",")}]"
        } catch (e: Exception) {
            "[]"
        }
    }

    /** Log from JS for debugging */
    @JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("CarTuneJS", message)
    }
}
