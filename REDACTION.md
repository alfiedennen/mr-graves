# Redaction record

A transparent log of what was scrubbed when this code was extracted from
its original homes (`harold-himself/harold_android/wakeword_training/`
and `harold-on-wall/android/app/src/main/`) to make it safe for public
release.

The originals continue to live in those private repositories; this repo
is the generic-ised, shareable version.

## What changed

### `android/WakewordService.kt`

| Original | Public version | Reason |
|---|---|---|
| `package road.harold.app` | `package com.example.wakeword` | Generic Android package convention; user search/replaces with their own |
| Action strings `road.harold.app.WAKEWORD_*` | `com.example.wakeword.WAKEWORD_*` | Match the new package |
| Notification channel IDs `harold_wakeword`, `harold_wake_fired` | `wakeword_listening`, `wakeword_fired` | Generic |
| Notification text `"Harold"` / `"Listening for 'Mr Graves'"` / `"Harold listening"` | `"Wake-word listener"` / `"Listening for the trained wake word"` / `"Wake-word listening"` | Generic |
| Wake-lock tag `"harold:wakeword-listen"` | `"wakeword:listen"` | Generic |
| Comment references to "Harold" persona | Re-written generically (with one explicit "the wake word here defaults to Mr Graves" intro paragraph because that IS what the bundled model is) | Lets the code be useful for any wake word |
| `MainActivity` references | Kept as `MainActivity` — that's the conventional Android activity class name. Code comments tagged `// Replace MainActivity::class.java with your own activity class.` | Standard convention; consumer adapts trivially |

### `android/WakewordPlugin.kt`

| Original | Public version | Reason |
|---|---|---|
| `package road.harold.app` | `package com.example.wakeword` | Same as service |
| `@CapacitorPlugin(name = "HaroldWakeword")` | `@CapacitorPlugin(name = "Wakeword")` | Generic plugin name |
| References to `HaroldWakeword` in JS-callable docstrings | `Wakeword` | Match new plugin name |
| Doc comment "MainActivity handles..." | "Your MainActivity should handle..." | Reframe for arbitrary consumer |

### `android/WakewordBootReceiver.kt`

| Original | Public version | Reason |
|---|---|---|
| `package road.harold.app` | `package com.example.wakeword` | Same as service |
| SharedPreferences key `"harold_wakeword"` | `"wakeword_listener_prefs"` | Generic |
| Comments referencing `_syncWakewordToAppState` (a specific JS function in the original app) | Generic phrasing "your JS-side sync routine" | Don't presume a specific implementation |

### Documentation files (new — not extracted)

`README.md`, `docs/deploy-android.md`, `docs/tune.md`, `docs/postmortem-v1.md`, `docs/how-it-works.md`, `docs/known-limits.md`, `docs/train.md`, `models/README.md`, `android/README.md` — all written fresh for this repo. They draw on the same lessons captured in the haroldathome.com private repo's memory entries (notably `feedback_mr_graves_v2_training_2026_05_09.md`) but are not direct extracts.

### Bundled ONNX models

- `models/mr_graves.onnx` (790 KB) — neural network weights, no PII. Trained on a corpus that included 62 real recordings of one person (the project author) saying "Mr Graves"; this is documented in `models/README.md` as a fitness-for-purpose caveat (the model fires more reliably on the original speaker's voice than on a generic user's).
- `models/_archive_v1_failed.onnx` (790 KB) — the failed first training attempt, kept as a teaching artefact paired with `docs/postmortem-v1.md`.

Neither file contains audio data or identifiable speech samples — only the trained weights of small neural networks.

## What was NOT shipped

- The `harold-on-wall` Capacitor app source — that's a complete app, far beyond the scope of this wake-word piece. Extracted from it: only the three Kotlin files for the wake-word listener.
- Voice samples used to train the bundled model (62 real recordings of the author + ~4700 synthetic samples) — privacy + size considerations.
- The data-prep scripts (`elevenlabs_generate.py`, `piper_generate.py`, `record_voice.py`) — useful for training but their inclusion was deferred per scope decision; will appear in a v2 of this repo or a sibling.
- The full set of MainActivity wake-word handling JS (the in-app behaviour after detection — STT chains, conversation routing, etc.) — out of scope for the wake-word listener itself.
- The `pre-trained_models/` cache that openwakeword pip-installs — readers download themselves, properly attributed to upstream.

## Verification before push

The `.githooks/pre-commit` hook (armed with `git config core.hooksPath .githooks` after clone) scans the staged diff for the patterns documented above plus generic secret-shape patterns. Bypass is `--no-verify`; never used legitimately.

## Licence audit

- All code files in this repo: MIT (LICENSE)
- All documentation, prose, screenshots: CC-BY-NC 4.0 (LICENSE-CONTENT)
- ONNX model weights: treated as code-equivalent under MIT
- No third-party code is bundled. ONNX Runtime Android is a Maven dependency. The two upstream openWakeWord ONNX models (melspectrogram, embedding) are documented but pulled by the user from `pip install openwakeword`.
