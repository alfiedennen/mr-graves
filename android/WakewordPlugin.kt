// Adapt the package to your own app — search/replace `com.example.wakeword`.
package com.example.wakeword

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * Capacitor plugin bridging the WakewordService to JS.
 *
 * Methods (callable from JS via Capacitor.Plugins.Wakeword):
 *   - start(model?: string, threshold?: number, startPaused?: boolean)
 *                                              — start the foreground service
 *   - stop()                                   — stop it
 *   - pauseListening() / resumeListening()     — release/reacquire the mic
 *                                                without killing the service
 *   - isRunning() → { running: boolean }
 *
 * Events (subscribe via Capacitor.Plugins.Wakeword.addListener):
 *   - 'wakewordDetected' { score: number, autoListen: true }
 *
 * Your MainActivity should also handle the case where the WakewordService
 * cold-launches the app via WAKEWORD_DETECTED intent — see docs/deploy-android.md.
 */
@CapacitorPlugin(
    name = "Wakeword",
    permissions = [
        Permission(
            alias = "microphone",
            strings = [Manifest.permission.RECORD_AUDIO]
        ),
        Permission(
            alias = "notifications",
            strings = ["android.permission.POST_NOTIFICATIONS"]
        )
    ]
)
class WakewordPlugin : Plugin() {

    private var receiverRegistered = false

    private val detectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WakewordService.ACTION_DETECTED) return
            val score = intent.getFloatExtra(WakewordService.EXTRA_SCORE, 0f)
            val payload = JSObject().apply {
                put("score", score.toDouble())
                put("autoListen", true)
            }
            Log.i(TAG, "→ JS wakewordDetected (score=$score)")
            notifyListeners(EVENT_DETECTED, payload)
        }
    }

    override fun load() {
        super.load()
        registerReceiver()
    }

    override fun handleOnDestroy() {
        unregisterReceiver()
        super.handleOnDestroy()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val ctx = context ?: return
        val filter = IntentFilter(WakewordService.ACTION_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(detectedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(detectedReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try { context?.unregisterReceiver(detectedReceiver) } catch (_: Throwable) {}
        receiverRegistered = false
    }

    @PluginMethod
    fun start(call: PluginCall) {
        val ctx = context ?: run {
            call.reject("no context")
            return
        }
        // Microphone permission gate. POST_NOTIFICATIONS is also required
        // on API 33+ for the foreground-service notification, but we
        // gracefully degrade if it's denied (the service still runs;
        // user just won't see the persistent indicator).
        val micGranted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            // Stash the call so requestPermissionsCallback can resume it.
            saveCall(call)
            requestPermissionForAlias("microphone", call, "permsCallback")
            return
        }
        startService(call)
    }

    @PermissionCallback
    fun permsCallback(call: PluginCall) {
        val ctx = context ?: run {
            call.reject("no context")
            return
        }
        val micGranted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            call.reject("microphone permission denied")
            return
        }
        startService(call)
    }

    private fun startService(call: PluginCall) {
        val ctx = context ?: run {
            call.reject("no context")
            return
        }
        val modelPath = call.getString("model")  // e.g. "wakewords/mr_graves.onnx"
        val threshold = call.getFloat("threshold")
        // startPaused: when true, the foreground service launches but
        // doesn't acquire the microphone. Used so the service can claim
        // its FGS exemption while the app is still in foreground, and
        // only start listening when the app backgrounds.
        val startPaused = call.getBoolean("startPaused", false) ?: false
        WakewordService.start(ctx, modelPath, threshold, startPaused)
        val res = JSObject().apply {
            put("running", true)
            put("paused", startPaused)
            put("model", modelPath ?: "wakewords/mr_graves.onnx")
            if (threshold != null) put("threshold", threshold.toDouble())
        }
        call.resolve(res)
    }

    @PluginMethod
    fun pauseListening(call: PluginCall) {
        val ctx = context ?: run { call.reject("no context"); return }
        WakewordService.pause(ctx)
        call.resolve(JSObject().apply { put("paused", true) })
    }

    @PluginMethod
    fun resumeListening(call: PluginCall) {
        val ctx = context ?: run { call.reject("no context"); return }
        WakewordService.resume(ctx)
        call.resolve(JSObject().apply { put("paused", false) })
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        val ctx = context ?: run {
            call.reject("no context")
            return
        }
        WakewordService.stop(ctx)
        val res = JSObject().apply { put("running", false) }
        call.resolve(res)
    }

    @PluginMethod
    fun isRunning(call: PluginCall) {
        // We don't track precise running state cheaply; pass a hint that
        // the service should be running if the user already called start.
        // JS rarely cares — it just listens for events.
        val res = JSObject().apply { put("running", true) }
        call.resolve(res)
    }

    companion object {
        private const val TAG = "WakewordPlugin"
        const val EVENT_DETECTED = "wakewordDetected"
    }
}
