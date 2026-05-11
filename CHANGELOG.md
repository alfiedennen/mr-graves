# Changelog

## 1.0.0 — 2026-05-11

Initial public release. Extracted from the haroldathome.com private
monorepo (specifically `harold-himself/harold_android/wakeword_training/`
for the trained ONNX, and `harold-on-wall/android/app/src/main/` for the
runtime Kotlin) and generic-ised for public use.

### What's in 1.0.0

- **Android Kotlin runtime** (locked May 2026):
  - `WakewordService.kt` — foreground service, openWakeWord 3-stage pipeline, NNAPI-delegated inference, full-screen-intent wake response, pause/resume by mic release
  - `WakewordPlugin.kt` — Capacitor plugin variant
  - `WakewordBootReceiver.kt` — boot-time SharedPreferences hook (no auto-start; Android 14+ blocks mic-FGS from boot)
- **AndroidManifest snippet** with all required permissions + component declarations
- **Reference trained `mr_graves.onnx`** (790 KB) — the working v2 model
- **Failed `_archive_v1_failed.onnx`** as a teaching artefact, paired with the post-mortem doc
- **Comprehensive docs**: deploy / tune / postmortem / how-it-works / known-limits / train

See [haroldathome.com/mr-graves](https://haroldathome.com/mr-graves) for the
narrative version of the wake-word concept and the formal-vs-casual
address pattern.

### What this repo deliberately doesn't have

A trainer. The companion repo
[alfiedennen/openwakeword-colab-2026](https://github.com/alfiedennen/openwakeword-colab-2026)
holds the v2-methodology trainer notebook. Train there; deploy with this.
