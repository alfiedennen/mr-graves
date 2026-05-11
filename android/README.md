# android/

Reference Android implementation of an openWakeWord 3-stage wake-word listener — foreground service + Capacitor plugin variant + boot receiver.

## Files

| File | Purpose | Lines |
|---|---|---|
| `WakewordService.kt` | The foreground service. Loads three ONNX models (mel → embedding → classifier), captures mic audio, runs inference, fires a broadcast on detection, launches your activity over the lockscreen via full-screen-intent | ~660 |
| `WakewordPlugin.kt` | Capacitor plugin glue. Bridges the service to JS via `Capacitor.Plugins.Wakeword`. Handles permission flow. Forwards detections as `wakewordDetected` events | ~190 |
| `WakewordBootReceiver.kt` | Boot-time hook. Logs the boot event; does NOT auto-start the service (Android 14+ blocks mic-FGS from boot — see the Kotlin file's docstring) | ~60 |
| `AndroidManifest-snippet.xml` | Permissions + component declarations to drop into your app's manifest | ~80 |

## Drop-in instructions

1. Copy all three `.kt` files into your Android project under `android/app/src/main/java/com/example/wakeword/`. Adapt the package name (search/replace `com.example.wakeword` with your own) — the corresponding action-string namespacing inside the Kotlin will follow.
2. Merge `AndroidManifest-snippet.xml` into your existing `AndroidManifest.xml`. Add the permissions, register the service + receiver, and add the `showWhenLocked` + `turnScreenOn` attributes to your launch activity.
3. Drop the three ONNX models into `android/app/src/main/assets/wakewords/`. See `../models/README.md` for what they are and where the upstream two come from.
4. Add `com.microsoft.onnxruntime:onnxruntime-android` to your app's `build.gradle` dependencies. The reference version we ship against is 1.17.x.
5. If you're using Capacitor, the plugin is registered automatically via the `@CapacitorPlugin(name = "Wakeword")` annotation. Call from JS:
   ```js
   import { registerPlugin } from '@capacitor/core';
   const Wakeword = registerPlugin('Wakeword');

   await Wakeword.start({ model: 'wakewords/mr_graves.onnx', threshold: 0.85 });
   Wakeword.addListener('wakewordDetected', ({ score }) => {
     console.log('Heard the wake word at score', score);
   });
   ```
6. If you're NOT using Capacitor, drop `WakewordPlugin.kt` and call the service directly:
   ```kotlin
   WakewordService.start(this, "wakewords/mr_graves.onnx", threshold = 0.85f)
   // Receive detections via a BroadcastReceiver listening for
   // ACTION_DETECTED in your own package.
   ```

## Why these specific design choices

The full deep-dive is in `../docs/how-it-works.md`. Quick highlights of the deliberate decisions encoded in this code:

- **Foreground service of type "microphone"**, not a regular background service. Required by Android 14+ for any mic access from background.
- **No PARTIAL_WAKE_LOCK.** The FGS itself prevents Doze for our process; an explicit wakelock just forces the CPU on between 80ms inference hops, halving idle battery. (A commented-out helper is included if you decide you need it.)
- **NNAPI delegate, with CPU fallback.** Tries to push inference onto the device's NPU/DSP for ~3× speed and ~3× lower power. Falls back to CPU automatically on devices that don't support NNAPI for the given ops.
- **Full-screen-intent notification, not direct startActivity.** Android 14's BAL_BLOCK refuses `startActivity` from a background FGS; the standard workaround for voice assistants is a high-priority notification with `setFullScreenIntent`. Requires `USE_FULL_SCREEN_INTENT`. We declare `CATEGORY_CALL` to maximise the chances of the system honouring the FSI without manual user grant.
- **Pause/resume via mic release**, not service kill. When `pauseListening()` is called, the service releases the AudioRecord (no green dot, no mic indicator) but stays alive — the rolling buffers and ONNX state survive, so resume is cheap. This is what gives the "wake-word only when app backgrounded" UX without re-paying the cold-start cost every time.
- **Float audio NOT normalised to [-1, 1].** openWakeWord's melspectrogram model expects raw PCM-range float (`audio.astype(np.float32)` without `/32768`). Match that or wake-word accuracy diverges from your colab eval.
- **Refractory + trigger-frames detection logic.** A single-frame above-threshold fires; a 1500ms refractory ignores follow-on frames so one wake event doesn't cascade. Tuneable per-deployment.

## Tested against

- Pixel 9 Pro (Android 15)
- Pixel 6 (Android 14, wall-mounted always-on)
- Capacitor 8.x

Other devices / Android versions not actively tested. PRs with results welcome.
