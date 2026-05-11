# Train your own classifier

This repo holds the **deployment** side of the wake-word stack. The **trainer** is its own dedicated repo.

## Where to train

→ [**alfiedennen/openwakeword-colab-2026**](https://github.com/alfiedennen/openwakeword-colab-2026)

A bulletproof, run-all-and-walk-away Colab notebook. Two lines to edit, one button to press, ~75-90 minutes on Colab Pro for ~$0.

## What you change in the notebook

```python
TARGET_PHRASE = ['mr graves', 'mister graves']   # what your wake word is
MODEL_NAME    = 'mr_graves'                       # output filename + dirs
```

Then **Runtime → Run all** and walk away. The last cell auto-downloads `<MODEL_NAME>.onnx`.

## What the trainer does that matters

The trainer in openwakeword-colab-2026 implements the **v2 methodology** that produced the working `mr_graves.onnx` shipped here. Critical features that distinguish it from a naïve trainer:

- **3-stage learning rate** (1e-4 → 1e-5 → 1e-6)
- **Negative-weight ramp** 1 → 1500 over training
- **Hard-negative mining** — at each epoch, pick the negatives the model is currently most confused about and re-weight them
- **False-fires-per-hour validation** against ACAV100M continuous-audio slices (~11 hours of speech, music, noise)
- **90/90/10 percentile checkpoint ensemble averaging**
- **Sigmoid baked into the exported ONNX** (so threshold 0.5 means what you think it means)

The first attempt at training Mr Graves did NONE of these things, scored 90% test accuracy, and fired every two seconds on the kettle. See `postmortem-v1.md` for that story.

## After training — drop the ONNX into this repo's deployment

1. Download `<MODEL_NAME>.onnx` from the Colab notebook
2. Drop into your Android project's `android/app/src/main/assets/wakewords/<MODEL_NAME>.onnx`
3. Pass it to the listener:
   ```js
   await Wakeword.start({ model: 'wakewords/<MODEL_NAME>.onnx', threshold: 0.85 });
   ```
4. See `tune.md` for production threshold tuning

## What this repo provides that the trainer doesn't

- **End-to-end Android deployment** — Kotlin foreground service + Capacitor plugin variant
- **The 3-stage runtime pipeline** in production-ready Kotlin
- **Threshold tuning methodology** — `tune.md`
- **The v1 → v2 failure narrative** — `postmortem-v1.md`
- **A reference trained `mr_graves.onnx`** to verify your runtime works before you train your own

## What the trainer provides that this repo doesn't

- The actual training code
- Synthetic positive-sample generation via Piper + ElevenLabs
- ACAV100M slicing for validation
- The hand-rolled PyTorch loop replacing openWakeWord's bit-rotted `auto_train`

The two repos are designed to be used together. Train in openwakeword-colab-2026; deploy with this.

## See also

- [openwakeword (upstream)](https://github.com/dscripka/openWakeWord) — the framework this builds on
- [microwakeword-trainer](https://github.com/alfiedennen/microwakeword-trainer) — sibling stack for ESP32 wake words (TensorFlow Lite Micro instead of ONNX, ~60 KB models, runs on the Atom Echo and similar tiny boards). Different framework, same methodology.
