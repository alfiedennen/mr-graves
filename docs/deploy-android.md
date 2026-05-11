# Deploy on Android

End-to-end recipe to take this code from `git clone` to a phone with the wake-word listener running and detecting "Mr Graves" within ~30 minutes.

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- An Android project targeting min SDK 24 (Android 7.0) or higher — recommended target SDK 34 (Android 14) so you get the full BAL_BLOCK behaviour the code is written against
- A device or emulator. The bundled reference model trained against a single voice — for the smoothest first-run experience, test on a physical device with someone speaking in approximately a British male voice. (For your own production, train your own model on real samples — see `train.md`.)
- Microphone permission grantable on the test device (some emulators don't expose a mic)
- For Capacitor users: Capacitor 8.x — earlier versions need a JDK rollback. Capacitor 8 declares VERSION_21 in compileOptions; install Temurin 21 or equivalent

## Step 1 — drop the Kotlin in

```
android/app/src/main/java/com/example/wakeword/
├── WakewordService.kt
├── WakewordPlugin.kt           ← Capacitor users only
└── WakewordBootReceiver.kt
```

If your app's package isn't `com.example.wakeword`, search/replace it with your own throughout all three files. The action-string namespacing inside the Kotlin files (e.g. `com.example.wakeword.WAKEWORD_DETECTED`) updates with the same replacement.

## Step 2 — drop the models in

```
android/app/src/main/assets/wakewords/
├── melspectrogram.onnx           ← from `pip install openwakeword`, see ../models/README.md
├── embedding_model.onnx          ← same source
└── mr_graves.onnx                ← bundled in this repo at ../models/mr_graves.onnx
```

For your own wake word, replace `mr_graves.onnx` with whatever you trained.

## Step 3 — merge the manifest

Open `android/app/src/main/AndroidManifest.xml` and merge the contents of `../android/AndroidManifest-snippet.xml`:

- Add the seven `uses-permission` declarations
- Add `android:showWhenLocked="true"` and `android:turnScreenOn="true"` to your launch activity
- Register the `WakewordService` with `foregroundServiceType="microphone"`
- Register the `WakewordBootReceiver` with the `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` intent filters

## Step 4 — add the ONNX runtime dependency

In `android/app/build.gradle`:

```gradle
dependencies {
    implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.17.1'
}
```

(Newer 1.18+ versions also work; 1.17.1 is the version this code is verified against.)

## Step 5 — start the listener

### Capacitor / JS

```js
import { registerPlugin } from '@capacitor/core';
const Wakeword = registerPlugin('Wakeword');

// Request mic permission + start the service
const { running } = await Wakeword.start({
  model: 'wakewords/mr_graves.onnx',
  threshold: 0.85,
});

// Listen for detections
Wakeword.addListener('wakewordDetected', ({ score }) => {
  console.log('Wake word at score', score);
  // Your handler — start STT, show a UI, navigate, whatever
});

// Pause (release the mic without stopping the service)
await Wakeword.pauseListening();

// Resume (reacquire the mic)
await Wakeword.resumeListening();

// Stop the service entirely
await Wakeword.stop();
```

### Native Android (no Capacitor)

```kotlin
import com.example.wakeword.WakewordService

WakewordService.start(this,
    modelAssetPath = "wakewords/mr_graves.onnx",
    threshold = 0.85f
)

// Receive detections via a BroadcastReceiver in your own package:
val filter = IntentFilter(WakewordService.ACTION_DETECTED)
registerReceiver(object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val score = intent?.getFloatExtra(WakewordService.EXTRA_SCORE, 0f) ?: 0f
        Log.i("MyApp", "wake word at $score")
    }
}, filter, Context.RECEIVER_NOT_EXPORTED)
```

## Step 6 — verify it works

```sh
adb logcat | grep -E "WakewordService|WakewordPlugin"
```

Expected log lines on a successful start:

```
WakewordService: START (paused=false) — service launching, listenActive=true
WakewordService: ONNX SessionOptions: NNAPI delegate enabled
WakewordService: ONNX models loaded (wake = wakewords/mr_graves.onnx)
WakewordService: AudioRecord acquired — listening
```

Then say "Mr Graves" near the phone. Expected:

```
WakewordService: WAKE — score=0.99664426
WakewordPlugin: → JS wakewordDetected (score=0.99664426)
```

If you see no `WAKE` lines after speaking the phrase 5+ times in normal voice, see `tune.md` for threshold debugging.

## Step 7 — tune threshold for production

The code defaults to `THRESHOLD = 0.5`, which is openWakeWord's reference (sigmoid baked into the exported ONNX). On real-room ambient, **raise to 0.85** to crush the false-fire rate.

See `tune.md` for the full tuning methodology + the false-fire diagnostic flow.

## Common gotchas

| Symptom | Cause | Fix |
|---|---|---|
| `Foreground service started from background can not have microphone access` | You called `WakewordService.start()` from a backgrounded process or BootReceiver | Start from a foregrounded activity, or rely on the user's next foreground app launch (the BootReceiver is documented to defer this) |
| Mic green dot appears even when `pauseListening()` was called | App is in the foreground; AudioRecord is active by design | Call `pauseListening()` from your activity's `onResume()` and `resumeListening()` from `onStop()` for the standard "wake-word only when backgrounded" UX |
| Wake fires constantly on background noise | Threshold too low OR model not appropriate for your environment | Raise threshold to 0.85+; if still failing, see `postmortem-v1.md` for the deeper failure pattern |
| Wake never fires at all | Threshold too high OR phrase not in the trained vocabulary | Drop threshold to 0.3 to confirm the pipeline is alive; if still nothing, run with `hey_jarvis.onnx` (also pre-trained, ships with openWakeWord) to verify the pipeline end-to-end before suspecting your custom model |
| `BAL: Background activity launch blocked` warnings | Expected on Android 14+ | The full-screen-intent path (Path 1 in `fireDetection`) is the reliable channel — the direct `startActivity` is best-effort and most of the time will get blocked. Both paths run in parallel |
| Full-screen intent doesn't actually open the app | `USE_FULL_SCREEN_INTENT` permission denied | On Android 14+, apps outside the calling/alarm categories need user grant via Settings → Apps → Special app access → Allow full screen notifications |
| ONNX models load but inference always returns 0 | PCM scaling — you normalised audio to [-1, 1] | Don't normalise. The Kotlin code passes raw PCM range floats; the model handles its own scaling |

## Production checklist

- [ ] Threshold raised to 0.85
- [ ] Refractory raised if your use case wants more silence between fires (default 1500ms)
- [ ] `pauseListening()` called from activity foreground / `resumeListening()` from background — for the no-green-dot UX
- [ ] `USE_FULL_SCREEN_INTENT` permission user-granted on Android 14 test devices
- [ ] BOOT_COMPLETED behaviour understood — listener is dormant after reboot until next app open
- [ ] Battery impact measured (typical: ~2-4% per 24h with NNAPI, ~6-10% on CPU fallback)
