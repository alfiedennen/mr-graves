# How it works

The architectural deep-dive — the openWakeWord 3-stage pipeline, the Android-specific design choices, the production runtime decisions.

## The 3-stage pipeline

openWakeWord splits wake-word detection into three sequential ONNX models. This split is what makes it fast and small enough to run continuously on a phone.

```
Mic 16 kHz mono PCM
   │  AudioRecord, 1280-sample chunks (80 ms hops)
   ▼
melspectrogram.onnx           ← pre-trained, ships with openWakeWord
   │  emits 5 mel frames of 32 bins per 80 ms hop
   ▼  rolling buffer of 76 frames (~12.16 s)
embedding_model.onnx          ← pre-trained, ships with openWakeWord
   │  emits 1 × 96-dim embedding per call
   ▼  rolling buffer of 16 embeddings (~1.28 s)
mr_graves.onnx                ← custom-trained, the only piece you train
   │  emits a single float — sigmoid-activated score in [0, 1]
   ▼
if score > THRESHOLD over WINDOW frames → fire detection
```

Why three models, not one:

- The melspectrogram and embedding models are SHARED across all wake words. They're audio feature extractors — once you have a 96-d embedding of "what sound is in the last 1.28 seconds", a tiny classifier (~30 KB) is all you need to recognise a specific phrase.
- Train ten wake words → ten 30 KB classifiers + the same shared 600 KB feature extractors. Cheap, modular, swap-on-the-fly.

Total runtime cost: ~3 ms per 80 ms hop on Pixel 9 Pro NNAPI; ~10 ms on CPU. Both well under budget.

## Why a foreground service of type "microphone"

Android 14 reorganised microphone access. Any background app that wants the mic must be in a foreground service of type `microphone`, declared both in the manifest AND in the `startForeground()` call.

`WakewordService` is exactly that. The persistent low-priority notification is the mandatory cost of admission — Android requires a visible indicator that something is using the mic.

## Why no PARTIAL_WAKE_LOCK

A common reflex: take a `PARTIAL_WAKE_LOCK` so the CPU doesn't park between inference calls. This is the wrong instinct.

The foreground service itself prevents the OS from putting our process into Doze. Between inference hops (every 80ms), the CPU CAN park briefly — and that's fine, because `AudioRecord.read(BLOCKING)` will wake it up the moment a chunk is ready. Holding an explicit wakelock just keeps the CPU spinning between hops, doubling idle power for no benefit.

Verified empirically: dropping the wakelock cut idle battery draw from ~6%/24h to ~3%/24h on Pixel 6, identical detection accuracy.

## Why NNAPI delegate, with CPU fallback

NNAPI (Neural Networks API) is Android's hardware-acceleration interface. On Pixels, NNAPI delegates to the Tensor chip's TPU/DSP. On other devices it might use the Mali GPU, the Hexagon DSP, or fall back to CPU.

For these specific models (~30 KB classifier, ~250 KB embedding, ~250 KB melspectrogram), NNAPI is ~3× faster than CPU and ~3× lower power. Worth claiming.

But not every device supports every op. The code wraps `addNnapi()` in try/catch and falls back to CPU silently if NNAPI compilation fails.

## Why full-screen-intent for the wake response

Android 14 introduced **BAL_BLOCK** — Background Activity Launch blocking. A foreground service can no longer call `startActivity()` to launch its own UI. This is a direct response to surveillance apps abusing FGS to draw over the user's current task.

The standard workaround for genuinely-legitimate cases (voice assistants, alarm apps, calls) is a high-priority notification with `setFullScreenIntent(pi, true)`. The system itself launches the activity when the notification is fired — the launch isn't "from" the FGS in the BAL sense, so it isn't blocked.

`WakewordService.fireDetection()` runs both paths in parallel:
1. **Path 1 (reliable)**: full-screen intent notification. Almost always succeeds on Android 14+
2. **Path 2 (best-effort)**: direct `startActivity`. Will hit BAL_BLOCK most of the time; only succeeds if the app happens to already be in a recent-foreground state

We declare `CATEGORY_CALL` on the notification to maximise the chance the system honours the FSI without requiring the user to manually grant `USE_FULL_SCREEN_INTENT` via settings.

## Why pause/resume by mic release, not service kill

The "wake-word only when app backgrounded" UX (no green dot, no mic-in-use indicator while you're using the app normally) requires us to:

- KEEP the foreground service alive (so the mic-FGS exemption stays claimed)
- RELEASE the AudioRecord (so the green dot disappears)

When `pauseListening()` is called, the capture loop sees `listenActive = false` on the next tick, releases the AudioRecord, clears the rolling buffers (so a wake-word said immediately after resume doesn't partially match against pre-pause fragments), and idles in 200ms tick polling.

When `resumeListening()` is called, `listenActive` flips back to true, the loop reacquires AudioRecord, and inference resumes from a clean state.

Cost of resume: ~50ms (acquire + 76 fresh mel frames at 80ms hops to fill the embedding buffer). Acceptable.

## Why floats NOT normalised to [-1, 1]

This one bit me hard for a couple of hours.

openWakeWord's melspectrogram model expects float audio in **raw PCM range** (-32768 to 32767), not normalised to [-1, 1]. The reference notebook explicitly does:

```python
audio = audio.astype(np.float32)   # NOT audio.astype(np.float32) / 32768
```

The model has its own internal normalisation derived from the embedding model's expected input distribution (`(x / 10) + 2` after the mel output). Pre-normalising the PCM input means double-scaling, which silently corrupts the mel features and produces a model that "loads" but never fires.

`WakewordService.runCaptureLoop()` does:

```kotlin
for (i in 0 until read) pcmFloat[i] = pcm[i].toFloat()   // raw PCM range, no /32768
```

Match this exactly or your wake-word accuracy diverges from your colab eval.

## Why refractory + trigger-frames

The detection logic is two thresholds, not one:

- `score > THRESHOLD` (default 0.5, tune to 0.85 — see `tune.md`)
- `consecutiveAbove >= TRIGGER_FRAMES` (default 1, raise to 2-3 for stricter)
- `now - lastFireMs >= REFRACTORY_MS` (default 1500ms)

The refractory window prevents one wake-word event from cascading into N detections as the score stays high across multiple inference hops. At 80ms hops, an above-threshold score typically sustains for 5-10 frames during a wake-word; without refractory you'd get a detection per frame.

The trigger-frames threshold lets you trade latency for strictness. At 1 frame (default), detection is ~80ms after the wake word ends. At 3 frames, ~240ms. The Mr Graves listener uses 1 because the v2-trained model's score distribution is decisive enough that single-frame is reliable.

## Why ACTION_DETECTED is package-scoped

```kotlin
sendBroadcast(
    Intent(ACTION_DETECTED).apply {
        setPackage(packageName)   // <-- this
        putExtra(EXTRA_SCORE, score)
    }
)
```

`setPackage(packageName)` makes the broadcast EXPLICIT to our own package. Without this, on Android 8+ the implicit broadcast is silently dropped — the OS doesn't deliver implicit broadcasts to manifest-registered receivers as a battery optimisation.

Our broadcast is consumed by `WakewordPlugin` (via `registerReceiver` at runtime, dynamic, which IS allowed). Both work in tandem: plugin gets the JS event, package-scoped broadcast keeps it private to us.

## File map

| File | What it does |
|---|---|
| `android/WakewordService.kt` | The foreground service. Owns the AudioRecord, the 3 ONNX sessions, the rolling mel + embedding buffers, the detection logic |
| `android/WakewordPlugin.kt` | Capacitor plugin. Bridges to JS. Handles permission flow. Forwards detections as `wakewordDetected` events |
| `android/WakewordBootReceiver.kt` | Boot receiver. Logs only — does NOT auto-start the service (Android 14+ blocks mic-FGS from boot, by design) |
| `android/AndroidManifest-snippet.xml` | Permissions + component declarations to drop into your app's manifest |
| `models/mr_graves.onnx` | Pre-trained classifier for "Mr Graves". 790 KB. Reference / drop-in test |
| `models/_archive_v1_failed.onnx` | The first training attempt that fired every 2 seconds — kept as a teaching artefact, paired with `docs/postmortem-v1.md` |
