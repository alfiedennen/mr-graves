// Adapt the package to your own app — search/replace `com.example.wakeword`.
package com.example.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on wake-word listener — openWakeWord 3-stage inference pipeline,
 * running as an Android foreground service of type "microphone".
 *
 * The default trained classifier shipped alongside this code is
 * `wakewords/mr_graves.onnx` (the wake word "Mr Graves"). To use a
 * different word, train your own following the recipe at
 * https://github.com/alfiedennen/openwakeword-colab-2026 and pass its
 * asset path via the `model_path` Intent extra (or the Capacitor
 * plugin's `model` option).
 *
 *   Mic 16 kHz mono PCM
 *      │  AudioRecord, 1280-sample chunks (80 ms hops)
 *      ▼
 *   Melspectrogram model  (melspectrogram.onnx)
 *      │  emits 5 mel frames of 32 bins per 80 ms hop
 *      ▼  rolling buffer of 76 frames (~12.16 s)
 *   Embedding model       (embedding_model.onnx)
 *      │  emits 1 × 96-d embedding per call
 *      ▼  rolling buffer of 16 embeddings (~1.28 s)
 *   Wake-word classifier  (mr_graves.onnx)
 *      │  emits a single float — score in [0, 1]
 *      ▼
 *   if score > THRESHOLD over WINDOW frames → fire detection
 *
 * On detection the service (a) broadcasts ACTION_DETECTED inside the app
 * package so an in-process listener (e.g. a Capacitor plugin) can notify
 * JS, and (b) launches MainActivity with FLAG_ACTIVITY_NEW_TASK + a
 * full-screen-intent notification, which combined with
 * android:turnScreenOn + android:showWhenLocked on the activity gives
 * full-takeover-over-lockscreen.
 *
 * Stays alive across app backgrounding via foregroundServiceType=
 * "microphone" + a low-priority persistent notification.
 *
 * IMPORTANT — replace MainActivity::class.java references below with
 * your own activity class (the one you want launched on detection).
 */
class WakewordService : Service() {

    companion object {
        private const val TAG = "WakewordService"
        private const val NOTIFY_CHANNEL_ID = "wakeword_listening"
        private const val NOTIFY_ID = 0xCAFE
        // Separate high-importance channel for the "wake fired" full-screen
        // intent notification. Kept distinct from the persistent listener
        // notification so the user can mute the persistent one in Settings
        // without losing the activity-launch behaviour.
        private const val NOTIFY_CHANNEL_ID_WAKE = "wakeword_fired"
        private const val NOTIFY_ID_WAKE = 0xCAFF

        // Audio capture
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1280  // 80 ms @ 16 kHz

        // Pipeline shapes (openWakeWord conventions, v0.6+)
        private const val MEL_FRAMES_PER_HOP = 5
        private const val EMBEDDING_INPUT_FRAMES = 76     // 76 mel frames
        private const val EMBEDDING_DIM = 96
        private const val CLASSIFIER_INPUT_EMBEDDINGS = 16

        // Detection logic.
        // DEFAULT_THRESHOLD = 0.5 matches openWakeWord's reference (sigmoid
        // baked into the exported ONNX, so 0.5 is the natural cut). For
        // production, raise to ~0.85 to crush false-fire rate on real-room
        // ambient — see docs/tune.md.
        private const val DEFAULT_THRESHOLD = 0.5f
        private const val DEFAULT_REFRACTORY_MS = 1500L   // ignore further detections this long after a fire
        private const val DEFAULT_TRIGGER_FRAMES = 1      // single-frame above-threshold is enough; raise for stricter

        // Action strings — namespaced by package; if you've changed the
        // package, the search/replace covers these too.
        const val ACTION_START   = "com.example.wakeword.WAKEWORD_START"
        const val ACTION_STOP    = "com.example.wakeword.WAKEWORD_STOP"
        const val ACTION_PAUSE   = "com.example.wakeword.WAKEWORD_PAUSE"
        const val ACTION_RESUME  = "com.example.wakeword.WAKEWORD_RESUME"
        const val ACTION_DETECTED = "com.example.wakeword.WAKEWORD_DETECTED"
        const val EXTRA_SCORE = "score"
        const val EXTRA_START_PAUSED = "start_paused"

        // Asset paths (drop files into android/app/src/main/assets/wakewords/)
        private const val ASSET_MEL = "wakewords/melspectrogram.onnx"
        private const val ASSET_EMBED = "wakewords/embedding_model.onnx"
        // Filename of the trained wake-word — defaults to mr_graves.onnx
        // but is overridable via Intent extra "model_path".
        private const val ASSET_WAKEWORD_DEFAULT = "wakewords/mr_graves.onnx"

        fun start(context: Context, modelAssetPath: String? = null,
                  threshold: Float? = null, startPaused: Boolean = false) {
            val intent = Intent(context, WakewordService::class.java).apply {
                action = ACTION_START
                modelAssetPath?.let { putExtra("model_path", it) }
                threshold?.let { putExtra("threshold", it) }
                putExtra(EXTRA_START_PAUSED, startPaused)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakewordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Pause active mic capture without killing the service. The
         * AudioRecord is released — no green dot, no mic held — but
         * the foreground notification + ONNX state stay alive so we
         * can resume cheaply when the user backgrounds the app.
         */
        fun pause(context: Context) {
            val intent = Intent(context, WakewordService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        /** Reacquire the AudioRecord and resume the inference loop. */
        fun resume(context: Context) {
            val intent = Intent(context, WakewordService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }
    }

    private val running = AtomicBoolean(false)
    // listenActive=true means the capture loop holds AudioRecord and
    // processes audio. listenActive=false means it has released the
    // mic and is sleeping in a polling loop until resumed. This is the
    // flag that controls "no green dot when foregrounded".
    private val listenActive = AtomicBoolean(true)
    private var captureThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ONNX
    private var ortEnv: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embedSession: OrtSession? = null
    private var wakeSession: OrtSession? = null

    // Rolling buffers (ring-buffer-ish — we just truncate from the front)
    private val melBuffer = ArrayDeque<FloatArray>()         // each entry = 32-bin mel frame
    private val embedBuffer = ArrayDeque<FloatArray>()       // each entry = 96-d embedding

    // Tunables
    private var threshold = DEFAULT_THRESHOLD
    private var triggerFramesRequired = DEFAULT_TRIGGER_FRAMES
    private var refractoryMs = DEFAULT_REFRACTORY_MS
    private var lastFireMs = 0L
    private var consecutiveAbove = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (listenActive.compareAndSet(true, false)) {
                    Log.i(TAG, "PAUSE — releasing mic, service stays alive")
                    // The capture loop will release AudioRecord on next iteration.
                }
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (listenActive.compareAndSet(false, true)) {
                    Log.i(TAG, "RESUME — reacquiring mic")
                    // Capture loop sees listenActive=true and reacquires AudioRecord.
                }
                return START_STICKY
            }
            else -> {
                val modelPath = intent?.getStringExtra("model_path") ?: ASSET_WAKEWORD_DEFAULT
                threshold = intent?.getFloatExtra("threshold", DEFAULT_THRESHOLD) ?: DEFAULT_THRESHOLD
                val startPaused = intent?.getBooleanExtra(EXTRA_START_PAUSED, false) ?: false
                listenActive.set(!startPaused)
                Log.i(TAG, "START (paused=$startPaused) — service launching, " +
                        "listenActive=${listenActive.get()}")
                startForegroundCompat()
                startListening(modelPath)
            }
        }
        // STICKY so Android brings us back if the OS kills us under memory
        // pressure. Combined with WakewordBootReceiver this gives an
        // "always on" feel.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        ortEnv?.close()
        ortEnv = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIFY_CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            NOTIFY_CHANNEL_ID,
            "Wake-word listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent indicator that the wake-word listener is active"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        // Replace MainActivity::class.java with your own activity class.
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, WakewordService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID)
            .setContentTitle("Wake-word listener")
            .setContentText("Listening for the trained wake word")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(launchPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: must declare the foregroundServiceType in the
            // startForeground call OR fall back to the manifest-declared
            // type. We pass it explicitly to be safe across OEM ROMs.
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun startListening(modelAssetPath: String) {
        if (running.getAndSet(true)) return  // already running

        // Load ONNX models lazily so a failed open doesn't crash the service
        // — we'd rather show a notification with the error and let the user
        // re-trigger via the plugin.
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            // Try to delegate inference to NNAPI — on Pixel that's the Tensor
            // chip's TPU/DSP path, ~3× faster + ~3× lower power than CPU.
            // Falls back to CPU automatically if NNAPI is unavailable or
            // fails to compile a particular op.
            val sessOptsNnapi = OrtSession.SessionOptions().apply {
                try {
                    addNnapi()
                    Log.i(TAG, "ONNX SessionOptions: NNAPI delegate enabled")
                } catch (e: Throwable) {
                    Log.w(TAG, "NNAPI unavailable, falling back to CPU: ${e.message}")
                }
            }
            melSession = ortEnv!!.createSession(loadAsset(ASSET_MEL), sessOptsNnapi)
            embedSession = ortEnv!!.createSession(loadAsset(ASSET_EMBED), sessOptsNnapi)
            wakeSession = ortEnv!!.createSession(loadAsset(modelAssetPath), sessOptsNnapi)
            Log.i(TAG, "ONNX models loaded (wake = $modelAssetPath)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed loading ONNX models — aborting", e)
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // No PARTIAL_WAKE_LOCK — the foreground service itself prevents
        // Doze for our process, and an explicit wakelock just forces the
        // CPU on between inference hops (between 80ms reads). With the
        // wakelock gone the CPU can park ~half the time, halving idle draw.

        captureThread = Thread {
            try {
                runCaptureLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "Capture loop crashed", t)
            }
        }.apply {
            name = "wakeword-capture"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopListening() {
        if (!running.getAndSet(false)) return
        captureThread?.interrupt()
        captureThread = null
        try { melSession?.close() } catch (_: Throwable) {}
        try { embedSession?.close() } catch (_: Throwable) {}
        try { wakeSession?.close() } catch (_: Throwable) {}
        melSession = null
        embedSession = null
        wakeSession = null
        melBuffer.clear()
        embedBuffer.clear()
    }

    private fun loadAsset(path: String): ByteArray {
        return assets.open(path).use { it.readBytes() }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakeword:listen"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "WakeLock acquire failed (continuing without)", e)
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun runCaptureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, CHUNK_SAMPLES * 2 * 4)  // 4× headroom for slow consumers

        val pcm = ShortArray(CHUNK_SAMPLES)
        val pcmFloat = FloatArray(CHUNK_SAMPLES)

        // AudioRecord lifecycle is dynamic: we release it when paused
        // (no green dot, no mic held), reacquire when resumed. This is
        // what gives "wake-word only when app backgrounded" UX.
        var rec: AudioRecord? = null

        fun acquireRec(): AudioRecord? {
            @Suppress("MissingPermission")
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialised")
                try { r.release() } catch (_: Throwable) {}
                return null
            }
            r.startRecording()
            Log.i(TAG, "AudioRecord acquired — listening")
            return r
        }

        fun releaseRec() {
            try { rec?.stop() } catch (_: Throwable) {}
            try { rec?.release() } catch (_: Throwable) {}
            rec = null
            // Clear rolling buffers so we don't carry stale audio into the
            // next active session — otherwise the wake word said while
            // resumed-from-pause would partially match against pre-pause
            // mel/embedding fragments.
            melBuffer.clear()
            embedBuffer.clear()
            consecutiveAbove = 0
            Log.i(TAG, "AudioRecord released — paused")
        }

        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                if (!listenActive.get()) {
                    // Paused: release mic and idle in 200ms ticks until resumed.
                    if (rec != null) releaseRec()
                    Thread.sleep(200)
                    continue
                }
                if (rec == null) {
                    rec = acquireRec()
                    if (rec == null) {
                        // Bad state — back off and retry rather than spin.
                        Thread.sleep(500)
                        continue
                    }
                }
                val read = rec!!.read(pcm, 0, CHUNK_SAMPLES, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                // openWakeWord's melspectrogram model expects float audio
                // in raw PCM range (NOT normalised to [-1, 1]). The
                // upstream notebook explicitly does `audio.astype(np.float32)`
                // without /32768, and the model's own internal scaling
                // takes care of the rest. Match that to keep wake-word
                // accuracy identical to colab eval.
                for (i in 0 until read) pcmFloat[i] = pcm[i].toFloat()

                val melFrames = inferMel(pcmFloat)
                if (melFrames != null) {
                    for (frame in melFrames) melBuffer.addLast(frame)
                    while (melBuffer.size > EMBEDDING_INPUT_FRAMES + 32) {
                        melBuffer.removeFirst()
                    }
                    if (melBuffer.size >= EMBEDDING_INPUT_FRAMES) {
                        val embed = inferEmbedding(melBuffer)
                        if (embed != null) {
                            embedBuffer.addLast(embed)
                            while (embedBuffer.size > CLASSIFIER_INPUT_EMBEDDINGS + 8) {
                                embedBuffer.removeFirst()
                            }
                            if (embedBuffer.size >= CLASSIFIER_INPUT_EMBEDDINGS) {
                                val score = inferWake(embedBuffer)
                                if (score != null) handleScore(score)
                            }
                        }
                    }
                }
            }
        } finally {
            try { rec?.stop() } catch (_: Throwable) {}
            try { rec?.release() } catch (_: Throwable) {}
            Log.i(TAG, "Capture loop exited")
        }
    }

    private fun inferMel(pcmFloat: FloatArray): Array<FloatArray>? {
        val sess = melSession ?: return null
        val env = ortEnv ?: return null
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(pcmFloat),
            longArrayOf(1, CHUNK_SAMPLES.toLong())
        )
        try {
            val inName = sess.inputNames.iterator().next()
            val out = sess.run(mapOf(inName to tensor))
            try {
                // Output shape: (1, 1, MEL_FRAMES_PER_HOP, 32). Squeeze
                // the two singleton dims and apply openWakeWord's standard
                // scaling: `(x / 10) + 2`, derived from the embedding
                // model's expected input distribution.
                val raw = out[0].value as Array<Array<Array<FloatArray>>>
                val frames = raw[0][0]
                val scaled = Array(frames.size) { i ->
                    val f = frames[i]
                    FloatArray(f.size) { j -> (f[j] / 10f) + 2f }
                }
                return scaled
            } finally {
                out.close()
            }
        } finally {
            tensor.close()
        }
    }

    private fun inferEmbedding(mel: ArrayDeque<FloatArray>): FloatArray? {
        val sess = embedSession ?: return null
        val env = ortEnv ?: return null
        // Take the most recent EMBEDDING_INPUT_FRAMES frames.
        val start = mel.size - EMBEDDING_INPUT_FRAMES
        val flat = FloatArray(EMBEDDING_INPUT_FRAMES * 32)
        var p = 0
        for (i in start until mel.size) {
            val f = mel[i]
            System.arraycopy(f, 0, flat, p, f.size)
            p += f.size
        }
        // Embedding model expects (1, 76, 32, 1).
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flat),
            longArrayOf(1, EMBEDDING_INPUT_FRAMES.toLong(), 32, 1)
        )
        try {
            val inName = sess.inputNames.iterator().next()
            val out = sess.run(mapOf(inName to tensor))
            try {
                // Output shape (1, 1, 1, 96) → take the 96-vec.
                val raw = out[0].value as Array<Array<Array<FloatArray>>>
                return raw[0][0][0]
            } finally {
                out.close()
            }
        } finally {
            tensor.close()
        }
    }

    private fun inferWake(emb: ArrayDeque<FloatArray>): Float? {
        val sess = wakeSession ?: return null
        val env = ortEnv ?: return null
        val start = emb.size - CLASSIFIER_INPUT_EMBEDDINGS
        val flat = FloatArray(CLASSIFIER_INPUT_EMBEDDINGS * EMBEDDING_DIM)
        var p = 0
        for (i in start until emb.size) {
            val v = emb[i]
            System.arraycopy(v, 0, flat, p, v.size)
            p += v.size
        }
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flat),
            longArrayOf(1, CLASSIFIER_INPUT_EMBEDDINGS.toLong(), EMBEDDING_DIM.toLong())
        )
        try {
            val inName = sess.inputNames.iterator().next()
            val out = sess.run(mapOf(inName to tensor))
            try {
                // Most OWW classifiers emit a single (1, 1) score.
                return when (val v = out[0].value) {
                    is Array<*> -> {
                        val first = v[0]
                        if (first is FloatArray) first[0]
                        else (first as? Float) ?: 0f
                    }
                    is FloatArray -> v[0]
                    else -> 0f
                }
            } finally {
                out.close()
            }
        } finally {
            tensor.close()
        }
    }

    private fun handleScore(score: Float) {
        if (score > threshold) {
            consecutiveAbove++
        } else {
            consecutiveAbove = 0
        }
        if (consecutiveAbove < triggerFramesRequired) return
        val now = System.currentTimeMillis()
        if (now - lastFireMs < refractoryMs) return
        lastFireMs = now
        consecutiveAbove = 0
        Log.i(TAG, "WAKE — score=$score")
        fireDetection(score)
    }

    private fun fireDetection(score: Float) {
        // Broadcast for an in-process listener (e.g. a Capacitor plugin).
        sendBroadcast(
            Intent(ACTION_DETECTED).apply {
                setPackage(packageName)
                putExtra(EXTRA_SCORE, score)
            }
        )

        // Build the activity-launch intent that we want Android to fire.
        // Replace MainActivity::class.java with your own activity class.
        val launch = Intent(this, MainActivity::class.java).apply {
            action = ACTION_DETECTED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_SCORE, score)
            putExtra("auto_listen", true)
        }
        val pi = PendingIntent.getActivity(
            this, 2, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Path 1: full-screen-intent notification.
        // Android 14+ blocks `startActivity` from a backgrounded foreground
        // service (BAL_BLOCK). The standard workaround for "voice
        // assistant"-shaped apps is a high-priority notification with a
        // full-screen intent — the system itself launches the activity
        // when the notification is fired, so it isn't subject to BAL.
        // Requires USE_FULL_SCREEN_INTENT permission (declared in
        // manifest). On Android 14, apps outside the calling/alarm
        // categories need the user to manually grant the permission via
        // Settings → Apps → Special app access → Allow full screen
        // notifications. We declare CATEGORY_CALL to maximise the
        // chances of the system honouring the FSI without manual grant.
        val wakeNotif = NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID_WAKE)
            .setContentTitle("Wake-word listener")
            .setContentText("Wake word detected")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(8_000L)   // notification disappears after 8s
            .build()
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            ensureWakeNotificationChannel(nm)
            nm.notify(NOTIFY_ID_WAKE, wakeNotif)
        } catch (e: Throwable) {
            Log.w(TAG, "Posting wake notification failed", e)
        }

        // Path 2: best-effort direct launch. Works if app is somehow
        // already in a foregrounded-or-recent state (e.g. user just
        // opened then locked their phone). Will hit BAL_BLOCK most of
        // the time; that's expected. Path 1 is the reliable channel.
        try {
            startActivity(launch)
        } catch (e: Throwable) {
            Log.w(TAG, "startActivity from service failed (BAL): ${e.message}")
        }
    }

    private fun ensureWakeNotificationChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(NOTIFY_CHANNEL_ID_WAKE) != null) return
        val ch = NotificationChannel(
            NOTIFY_CHANNEL_ID_WAKE,
            "Wake fired",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fires when the wake word is detected — opens the app"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }
}
