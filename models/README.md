# models/

Three ONNX models are required at runtime — two pre-trained from openWakeWord, one custom-trained classifier per wake word.

## Files in this directory

| File | Size | Purpose |
|---|---|---|
| `mr_graves.onnx` | 790 KB | The custom-trained classifier for the wake word "Mr Graves". Reference / drop-in test model. **See caveat below.** |
| `_archive_v1_failed.onnx` | 790 KB | The first training attempt — it scored 90% on the test split and fired every two seconds on real-room ambient noise. Kept as a teaching artefact. See `../docs/postmortem-v1.md`. |

## Models you need from upstream

These two ship with the openWakeWord pip package. They are NOT bundled here — pull them once into your assets directory.

| File | Purpose |
|---|---|
| `melspectrogram.onnx` | Audio (16kHz PCM float, 1280-sample chunks) → 5 mel frames of 32 bins per 80 ms hop |
| `embedding_model.onnx` | 76 mel frames (32 bins each) → 96-dimensional embedding |

## Pulling the upstream two

```sh
pip install openwakeword
python -c "from openwakeword.utils import download_models; download_models()"
# Models then live at:
#   <site-packages>/openwakeword/resources/models/melspectrogram.onnx
#   <site-packages>/openwakeword/resources/models/embedding_model.onnx
```

Copy both into your Android project at `android/app/src/main/assets/wakewords/`.

## ⚠ Caveat on the bundled `mr_graves.onnx`

This model was trained on a corpus that included 62 real recordings of one person (the haroldathome project author) saying "Mr Graves". The synthetic majority of the training data (2000 ElevenLabs Italian-accented + 2712 Piper US-EN samples) gives it broad coverage, but **the model fires more reliably on the original speaker's voice than on a generic user's**.

For your own deployment:

- **For testing / verification only**: drop this in as-is. You'll know in 60 seconds whether your service + ONNX runtime + audio pipeline are wired correctly. Speak "Mr Graves" near the phone with audio enabled in `WakewordService`'s log (`adb logcat | grep WakewordService`).
- **For production**: train your own. The community-friendly trainer is at https://github.com/alfiedennen/openwakeword-colab-2026 — it takes ~75-90 minutes on Colab Pro for ~$0.

## What "training your own" looks like

1. Open the Colab notebook from openwakeword-colab-2026.
2. Edit two lines:
   ```python
   TARGET_PHRASE = ['your phrase', 'alt pronunciation']
   MODEL_NAME    = 'your_model_name'
   ```
3. Runtime → Run all. Walk away ~75 min.
4. Last cell auto-downloads `your_model_name.onnx`. Drop into `android/app/src/main/assets/wakewords/`.
5. Update `WakewordService.kt`'s `ASSET_WAKEWORD_DEFAULT` (or pass via Intent extra `model_path`).

## What's in `_archive_v1_failed.onnx`

A wake-word classifier trained the wrong way — naïve pos/neg balance, no hard-negative mining, no false-fires-per-hour validation, weight = 1 instead of ramping to 1500. It reports 90% test accuracy. Loaded into the WakewordService and run against a Pixel mic in a real room, it fires every two to three seconds on typing, footsteps, the dishwasher, conversation in the next room, and music from the kitchen.

You can load this and watch the failure happen in real time. It's a useful intuition-builder for the post-mortem.

## File integrity

Both `.onnx` files are committed as-is. They contain neural network weights only — no PII, no audio samples, no entity references. The pre-commit hook does not need to scan them; binary file diffing isn't useful here.
