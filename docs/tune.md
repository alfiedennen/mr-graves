# Tune for production

Threshold tuning is the difference between a wake word that works and a wake word that fires every two seconds on the kettle. This document is the methodology that took the Mr Graves listener from "unusable v1" to "13/13 fires in real conditions" — see `postmortem-v1.md` for the v1 failure shape.

## The default and why it's wrong

`WakewordService.kt` ships with `DEFAULT_THRESHOLD = 0.5f`. This is openWakeWord's reference value — the sigmoid is baked into the exported ONNX, so 0.5 is the natural cut between "kind of like the wake word" and "more wake word than not".

**For a quiet office with a high-quality model, 0.5 fires reliably on intent and rarely on noise.**

**In a real room, 0.5 will fire on:**
- Conversation in the next room
- The dishwasher
- Music with similar phonemes
- Footsteps on hard floor
- Typing
- Background TV

This is not a bug in the model. It's a calibration mismatch — the test split during training is a balanced corpus of clean positives and clean negatives, while production audio is dominated by ambient noise the model never had a chance to learn to ignore at the right threshold.

## The production setting

For Mr Graves running 24/7 on a wall-mounted Pixel 6 in a 1930s English semi-detached: **threshold = 0.85**.

This was arrived at by:

1. Training the model with the v2 methodology in `openwakeword-colab-2026` (false-fires-per-hour validation against ACAV100M continuous audio, NOT just balanced test accuracy)
2. Deploying with threshold = 0.5 and logging every fire for 24 hours
3. Observing 200+ false fires across the day, 30+ true positives
4. Plotting score distribution: false fires clustered around 0.5–0.7, true positives at 0.8–0.999
5. Setting threshold = 0.85 — the gap above the false-fire mode and below the true-positive median (~0.85 itself)
6. Re-deploying and observing **13 of 13 true fires** across a multi-room walking test, **zero false fires** over 12 hours of normal household activity

Median true-positive confidence at this threshold: ~0.85 (i.e. tightly clustered above the line, not scraping its underside). That's the right shape — a noisier model would have true positives skating just above the threshold, signalling that your training methodology hasn't separated the signal cleanly.

## The diagnostic flow

If your wake word is mis-firing in either direction, work this checklist:

### Symptom: fires too often (false positives)

1. **First check — log every fire with score**:
   ```sh
   adb logcat -s WakewordService | grep WAKE
   ```
2. **Are the scores low (0.5–0.7)?** → Threshold problem. Raise to 0.85 and re-test.
3. **Are the scores high (0.85+) on the false fires?** → Model problem. The model has genuinely learned a too-broad concept of the wake word. Re-train with the v2 methodology — see `postmortem-v1.md`.
4. **Specific times of day with bursts of false fires?** → Environmental. Look at what's happening — TV on? music? cooking? kettle? — and add hard-negative training samples of those exact sounds in your next training cycle.
5. **Wake word similar to a common phoneme pattern?** → "Mr Graves" is good in this regard ("Mr" is uncommon as an opener, "Graves" has unusual `gr-` consonant cluster). "Hey Buddy" or "Computer" would be much harder. If your wake word is too phonetically common, consider switching.

### Symptom: never fires on intent (false negatives)

1. **Drop threshold to 0.3 temporarily** and confirm the pipeline is alive. If still nothing fires, the pipeline is broken — see `deploy-android.md` § "Common gotchas".
2. **Confirm the embedding model is loaded correctly**: `WakewordService` logs `ONNX models loaded (wake = ...)` on successful start.
3. **Run with the `hey_jarvis.onnx` model** (ships with openWakeWord) to confirm the openWakeWord pipeline itself is functioning end-to-end. If `hey_jarvis` fires reliably and your custom model doesn't, the issue is your custom model.
4. **Speak the wake word 10 times in normal voice, log the scores**. If scores are 0.4–0.7 across the board, your threshold is too high — drop it. If scores are < 0.3 even on direct intent, retrain.

## Why higher than openWakeWord's recommended 0.5?

openWakeWord's documented threshold is 0.5 because it's calibrated against the public test sets, not against ambient continuous audio. The v2 trainer in `openwakeword-colab-2026` adds **false-fires-per-hour validation against ACAV100M** during training — a 11-hour slice of speech, music, noise, talk shows, podcasts. This shifts the score distribution under that load: the model learns to push real positives further above the line, and noise further below.

Net result: a v2-trained model deployed at threshold = 0.85 has roughly the same precision/recall as a vanilla openWakeWord model at 0.5 — but with a much wider safety margin.

## Other tuneables besides threshold

| Constant in `WakewordService.kt` | What it does | When to change |
|---|---|---|
| `DEFAULT_TRIGGER_FRAMES = 1` | How many consecutive frames must score above threshold before firing | Raise to 2–3 if you want stricter. At 80ms hops, requiring 3 consecutive above-threshold frames is ~240ms of sustained signal — useful for very picky deployments |
| `DEFAULT_REFRACTORY_MS = 1500L` | Minimum time between fires | Raise if your downstream handler can't cope with rapid-fire detections (e.g. an STT that takes 3s to start). Lower if you want crispier double-trigger detection |

## Adapting to your space

Different rooms have different acoustic profiles. The same model + threshold works differently in a kitchen (hard surfaces, water sounds) vs a bedroom (soft furnishings, low ambient). If you deploy across multiple rooms, consider:

- Per-room threshold tuning (the architecture allows it — pass `threshold` per `WakewordService.start()` call)
- Per-room hard-negative augmentation in retraining (record an hour of YOUR specific kitchen, your specific TV, your specific dishwasher, mix into the negatives at 4× weight)

The wake-word problem ultimately becomes an environment-specific tuning problem. The model is the floor; the threshold + hard-negative augmentation is the ceiling.
