# Post-mortem — when v1 fired every two seconds on the kettle

The first attempt at training the Mr Graves classifier was a model that **scored ninety-percent test accuracy and was completely unusable in production**.

This document is the failure shape, the gotchas behind it, and the recognition pattern that should help you avoid the same trip if you train your own.

The failed model is in this repo at `models/_archive_v1_failed.onnx` — you can load it into the WakewordService and watch the failure happen in real time.

## What v1 looked like during training

```
Epoch 50/50  loss: 0.034  val_acc: 0.901  val_recall: 0.873
Test set: accuracy 0.902, recall 0.871, precision 0.928
```

A perfectly normal-looking training curve. Loss dropped, accuracy climbed, the test split agreed. By every metric I had at training time, the model was good.

## What v1 looked like in production

Deployed to a real Pixel in a real room, listening continuously:

```
WAKE — score=0.62133294
WAKE — score=0.71244918
WAKE — score=0.55892310
WAKE — score=0.68021255
WAKE — score=0.59334411
WAKE — score=0.71284412
... fires every 2-3 seconds for 24 hours straight
```

**Every two to three seconds**, on:
- Typing
- Footsteps
- The dishwasher
- Conversation in the next room
- Music
- The kettle
- Silence with HVAC hum

Saying "Mr Graves" directly into the phone fired it, but no more reliably than the dishwasher did. The model had learned a concept of "audio with vaguely human-vocal energy in the right time range" — not "the specific phonemes of Mr Graves".

## Why the test split lied

The test split was a balanced 4000-sample corpus: 2000 positives (synthetic + a few real recordings), 2000 negatives (random clean speech segments).

The negatives were CLEAN SPEECH. Not background ambient. Not music. Not the dishwasher. **The model never had to learn to ignore the things that dominate a real room's acoustic profile.**

A wake word in production isn't asked "is this the wake word vs another sentence?" — it's asked "is this the wake word, vs the constant background sound of a real room, repeated 80 times a second for 24 hours?" Test on the wrong question, get the wrong answer.

## The six concrete gotchas behind v1

These specific things were wrong with the v1 trainer. Naming them so you can recognise them in your own training pipeline:

### 1. No false-fires-per-hour validation

v1 reported `val_acc` and `val_recall` only. Both look great even when the model is firing constantly on noise — the validation set is balanced, not representative.

**Fix**: validate against a long continuous audio corpus (we use ACAV100M slices — ~11 hours of speech, music, noise, talk shows, podcasts). Score by **false-fires-per-hour**, not accuracy. A model that scores < 1 false-fire-per-hour at threshold 0.5 is the bar. The v2 trainer does this; v1 didn't.

### 2. No hard-negative mining

v1 trained on the same balanced corpus every epoch. The model learned the easy negatives (random speech) and never had to push back on the hard ones (sounds that genuinely confuse it).

**Fix**: at each epoch, run the in-flight model against a large pool of negatives, pick the ones it's currently most confused about (loss > 0.001 typically — i.e. the model is currently outputting > 0 probability of wake word for them), include those in the next epoch at higher weight. The v2 trainer does this; v1 didn't.

### 3. Negative weight = 1, not ramped to 1500

In a balanced trainer, the loss contribution of a positive vs a negative is symmetric (weight = 1 each). But a wake word's production load is overwhelmingly negatives — millions of negatives per positive. The model's optimisation must reflect that asymmetry.

**Fix**: ramp the negative-class weight from 1 → 1500 over training. Late-epoch negatives carry 1500× the loss contribution of late-epoch positives. The model learns to be very confident about saying "no" before it's allowed to relax. The v2 trainer does this; v1 didn't.

### 4. Single learning rate

v1 used a single `lr = 1e-3` throughout. This is fine for fast convergence but produces a model that's good-on-average and bad on the hard cases — exactly the opposite of what wake-word detection needs.

**Fix**: 3-stage learning rate (1e-4 → 1e-5 → 1e-6). Each stage refines the model's separation of the borderline cases. The v2 trainer does this; v1 didn't.

### 5. Single final-checkpoint save

v1 saved only the model weights from the final epoch. If the final epoch happened to be on a slightly noisy gradient direction, that's the model that ships.

**Fix**: percentile-checkpoint ensemble averaging — save the 90th, 90th, and 10th percentile checkpoints across late-stage epochs and average their weights. Smooths out the final model's idiosyncrasies. The v2 trainer does this; v1 didn't.

### 6. Sigmoid not baked into the ONNX export

v1 exported the raw logits ONNX. Threshold "0.5" in production was meaningless — actually the threshold needed to be `sigmoid⁻¹(0.5) = 0` against the raw logit, but that's not what `WakewordService` was comparing.

**Fix**: bake `sigmoid` into the exported ONNX so the runtime score is in [0, 1] and 0.5 means what it should mean. The v2 trainer does this; v1 didn't.

## How to recognise this failure shape if it happens to you

Symptoms in order of speed-to-spot:

1. **Fires every few seconds in any real room with normal ambient.** Number one diagnostic — if your model is firing this often on a quiet room, it's a v1-class model.
2. **Above-threshold scores cluster around 0.5–0.7 on the false fires.** The model is uncertain. v2-trained models put true positives above 0.85 and false positives below 0.5; v1 puts everything in the murky middle.
3. **Saying the actual wake word fires it but at the same score range as background noise.** The model isn't separating signal from noise — it's just outputting "moderate energy, vocal range" indiscriminately.
4. **Test accuracy looks fine.** The v1 model scored 90% on its test split. Test accuracy is the thing that lies.

If you see these, **don't tune the threshold**. No threshold setting can rescue a model that hasn't learned the right concept. Re-train with the v2 methodology.

## What "training right" looks like

The v2 trainer in https://github.com/alfiedennen/openwakeword-colab-2026 is a hand-rolled PyTorch loop that mirrors openWakeWord's `auto_train` exactly:

- 3-stage learning rate (1e-4 → 1e-5 → 1e-6)
- Negative-weight ramp 1 → 1500 over training
- Hard-negative mining (only train on `loss > 0.001` negatives + `loss < 0.999` positives at each epoch)
- FP-per-hour validation against ACAV100M continuous audio
- 90/90/10 percentile checkpoint ensemble averaging
- Sigmoid baked into the exported ONNX

Result: the same `mr_graves` phrase, trained the right way, hit **13/13 fires in real conditions** with a median confidence of ~0.85 and **zero false fires over 12 hours of normal household activity** at threshold 0.85.

Same training corpus. Same wake word. Same test split. Different production behaviour by an order of magnitude.

The auto_train upstream path keeps surfacing bugs against `mmap_batch_generator` shape handling on current openwakeword `master` — that's why `openwakeword-colab-2026` ships a hand-rolled PyTorch loop reproducing auto_train's curriculum exactly. Read the trainer notebook in that repo for the line-by-line implementation.
