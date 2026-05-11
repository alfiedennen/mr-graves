# Known limits

The piece is locked at v1; the boundaries below are deliberate. PRs that work *within* them are welcome.

## Hard requirements

- **Android only.** The Kotlin reference implementation is Android-specific. iOS and web (ONNX-WASM) are sketched in the haroldathome.com piece but not in this repo. Both are real possibilities for future expansion — PRs welcome.
- **Min SDK 24 (Android 7.0).** The ONNX runtime, NNAPI delegate, and foreground-service APIs all require this. Earlier Android works in theory but isn't tested.
- **Target SDK 34+ recommended.** The BAL_BLOCK + full-screen-intent design is for Android 14+ behaviour. Earlier targets work but the wake-response behaviour is silently easier (no BAL to work around).
- **WAKE LOCK + RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE permissions.** All declared in the manifest snippet. Mic permission is user-grantable; users on Android 14+ also need to grant `USE_FULL_SCREEN_INTENT` via Settings if your app isn't in the calling/alarm category.

## Runtime constraints

- **CPU/memory budget**: ~3-10 ms inference per 80 ms hop (NNAPI vs CPU). ~50 MB RSS for the foreground service. Negligible network — all on-device.
- **Battery**: Measured ~3% per 24h on Pixel 6 with NNAPI delegate active. Fallback to CPU bumps to ~6%/24h. Without the foreground-service exemption (i.e. trying to run as a regular background service) the OS would Doze us within minutes, so FGS is non-negotiable.
- **One classifier model active at a time.** The architecture supports loading multiple, but the bundled service runs one. Multi-wake-word is doable — pass an array of classifiers, run inference on each, fire on whichever crosses threshold first — but the demo isn't in scope here.

## Model constraints

- **The bundled `mr_graves.onnx` is over-fitted to one voice.** Trained with 62 real recordings of one speaker mixed into ~4700 synthetic positives. Fires more reliably on the original speaker's voice than on a generic user's. For production, train your own — see `train.md` and the openwakeword-colab-2026 trainer.
- **Wake words shorter than ~700 ms don't work well.** openWakeWord's embedding window is ~1.28 s; for very short triggers (one-syllable words, single sounds), there's not enough phonetic content to anchor a confident classifier. "Mr Graves" at ~600 ms is borderline; "Mr Graves Sir" at ~900 ms would train more reliably.
- **Wake words too phonetically common will mis-fire.** "Hey Buddy" gets confused with "Hey buddy" said in conversation, "Hey body" in a podcast, etc. Choose a phrase whose phonetic profile is rare in your acoustic environment.

## Pipeline constraints

- **80 ms hop = 12.5 fps inference.** This is openWakeWord's reference. Higher rates would be lower-latency but break the model's expected feature timing.
- **Refractory of 1500 ms** is configurable but if you set it below ~500 ms, single wake-word events cascade into multiple detections (the above-threshold score sustains for ~5-10 hops during a real wake-word).
- **No wake-word disambiguation.** If you load the same model twice or two models with similar phonemes, both will fire and you'll get duplicate detections. Wake-word selection is your concern, not this code's.

## What's NOT in this repo

- **A trainer.** The trainer notebook is at https://github.com/alfiedennen/openwakeword-colab-2026 — comprehensive, single-cell-edit + walk-away.
- **Audio data prep.** Scripts to generate synthetic positives via Piper / ElevenLabs, or to record real positives, are in the haroldathome.com private repo and not extracted here. The trainer notebook handles synthetic generation internally; for real-voice augmentation, you'll need to build your own capture pipeline.
- **iOS / web variants.** Out of scope for v1. The ONNX models themselves are platform-agnostic; iOS would need a CoreML conversion + a Swift port of `WakewordService.kt`; web would need an ONNX-WASM runtime + a service-worker port.
- **Multi-platform Capacitor packaging.** The plugin here is a drop-in for an existing Capacitor Android project, not a standalone published `@capacitor/wakeword` package. Packaging it as one is straightforward (Capacitor docs cover the plugin scaffold) but isn't done here.
- **Diagnostic UI.** A small in-app overlay that shows live scores, threshold, and recent fires would help during tuning. Not in v1.

## Browser / device compatibility tested

| Device | Android version | Status |
|---|---|---|
| Pixel 9 Pro | 15 | ✓ tested in production |
| Pixel 6 (wall-mounted, always-on) | 14 | ✓ tested in production, multi-month uptime |
| Pixel 8 / 8 Pro | 14 | not tested |
| Samsung S2x / S2x Ultra | not tested | not tested |
| Other OEMs | not tested | NNAPI behaviour varies |

PRs with results from other devices very welcome.
