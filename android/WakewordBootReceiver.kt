// Adapt the package to your own app — search/replace `com.example.wakeword`.
package com.example.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log

/**
 * Boot-time hook for the wake-word listener.
 *
 * Originally we tried to auto-start the foreground service the moment
 * the device finished booting. Android 14+ refuses this with
 *   "Foreground service started from background can not have
 *    location/camera/microphone access"
 * — mic-typed FGS launches from BOOT_COMPLETED are explicitly blocked
 * to prevent surveillance after a reboot.
 *
 * So the receiver no longer attempts the start. It only logs the boot
 * event and notes that the user will get the listener back the next
 * time they open your app (your JS-side `_syncWakewordToAppState` should
 * read the persisted feature flag and start the service while the
 * activity is still foregrounded — which Android 14 allows).
 *
 * Net behaviour: after a reboot, wake-word is dormant until the user
 * next opens the app once. Then it rearms automatically.
 */
class WakewordBootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "WakewordBoot"
        const val PREFS = "wakeword_listener_prefs"
        const val PREF_AUTOSTART_ENABLED = "autostart_enabled"
        const val PREF_LAST_MODEL = "last_model"
        const val PREF_LAST_THRESHOLD = "last_threshold"

        fun setAutostart(ctx: Context, enabled: Boolean, model: String? = null, threshold: Float? = null) {
            val p: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.edit().apply {
                putBoolean(PREF_AUTOSTART_ENABLED, enabled)
                model?.let { putString(PREF_LAST_MODEL, it) }
                threshold?.let { putFloat(PREF_LAST_THRESHOLD, it) }
                apply()
            }
        }
    }

    override fun onReceive(ctx: Context?, intent: Intent?) {
        ctx ?: return
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = p.getBoolean(PREF_AUTOSTART_ENABLED, false)
        Log.i(TAG, "Boot received (action=$action, autostartFlag=$enabled). " +
                "Mic-FGS start from boot is blocked on Android 14+; deferring " +
                "rearm until next user open.")
        // Intentionally NOT calling WakewordService.start here — Android
        // would refuse with FGS-from-BAL. The next foreground app launch
        // re-establishes the service from JS via your sync routine.
    }
}
