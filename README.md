# Spam Shield

On-device SMS spam classifier for code-mixed (Hinglish/Tanglish) messages. No internet needed to
screen a message — a `BroadcastReceiver` runs the bundled `spam_detector.tflite` locally on every
incoming SMS. Only spam is ever reported to the telemetry backend, and only as text; safe messages
never leave the device.

Detects spam in English, Hindi, Hinglish, Tamil, Tanglish, Bengali, Punjabi, Marathi, Urdu and
several other languages, and is robust to the obfuscation spam uses (`C0ngratu1ati0ns`, `c1ick`).
On the real-data holdout: **precision 1.00, recall 0.95**. See `ml_pipeline/README.md` for how
that is measured and why the test-split number is the less meaningful one.

## What it does

- Screens every incoming SMS the moment it arrives (`SmsReceiver`), no need to open the app.
- Keeps a local history of every message screened — spam and safe alike — shown as a list on the
  home screen, newest first, with a SPAM/SAFE badge and confidence %.
- Long-press any message to correct a wrong verdict; the correction is sent to the backend so it
  can feed the next model retrain (see the root project README for the full loop).
- The FAB lets you run inference on arbitrary typed text without waiting for a real SMS.
- Toasts + a persistent local record when spam is blocked; a background WorkManager job retries
  any telemetry report that failed to send (no signal, backend down, etc).

## Setup

1. `local.properties` (gitignored) needs, in addition to `sdk.dir`:
   ```
   SERVER_BASE_URL=http://10.0.2.2:3000      # emulator → laptop localhost; use your LAN/ngrok URL on a real phone
   TELEMETRY_API_KEY=<same value as backend_telemetry/.env's API_KEY>
   ```
   Without these, the app falls back to `http://10.0.2.2:3000` / a dev placeholder key that won't
   authenticate against a real backend.
2. Run `backend_telemetry` (see its own README) before testing telemetry reporting.
3. Build & run as usual from Android Studio, or `./gradlew assembleDebug`.

## Testing without a second phone

Debug builds only (see `src/debug/AndroidManifest.xml`) expose an extra broadcast action so you
can trigger the full screening pipeline — classification, local history, telemetry report — over
adb.

The receiver must be addressed **explicitly with `-n`**. Android 8+ blocks *implicit* broadcasts
to manifest-declared receivers, so the intuitive `am broadcast -a <action>` form is silently
enqueued and never delivered. (Real SMS is unaffected: `SMS_RECEIVED` is a protected broadcast and
is exempt from that restriction.) Quote the body so the device-side shell does not word-split it:

```bash
adb shell am broadcast -n com.spamshield.app/.SmsReceiver \
  -a com.spamshield.app.DEBUG_TEST_SMS \
  --es sender "'VM-ALERTS'" \
  --es body "'Bhai lottery jeet gaya, turant click karo bit.ly/xyz'"
```

For non-Latin scripts, adb's argument encoding mangles the text; base64 it and decode on-device:

```bash
B64=$(printf '%s' 'आपका बैंक खाता आज बंद हो जाएगा, तुरंत KYC पूरा करें' | base64 -w0)
adb shell "B=\$(echo $B64 | base64 -d); am broadcast -n com.spamshield.app/.SmsReceiver \
  -a com.spamshield.app.DEBUG_TEST_SMS --es sender 'VK-KYC' --es body \"\$B\""
```

Check the app's message list, or `adb logcat -s SpamShield Telemetry`.

## Architecture

| File | Role |
|---|---|
| `SmsReceiver.kt` | `SMS_RECEIVED` broadcast receiver — classifies, persists, reports spam |
| `TextFeaturizer.kt` | Text → hashed feature ids. **Must match `ml_pipeline/text_features.py` exactly** |
| `SpamClassifier.kt` | Loads `spam_detector.tflite` + `model_meta.json`, runs inference |
| `data/MessageDbHelper.kt` / `MessageRepository.kt` | Local SQLite history + reactive `LiveData` |
| `MainActivity.kt` / `MessageAdapter.kt` | Message list UI, manual test dialog, correction dialog |
| `TelemetryClient.kt` | Reports to the backend (spam text, or a correction) |
| `TelemetrySyncWorker.kt` | Periodic retry for reports that failed to send immediately |

Why a `BroadcastReceiver` and not a `NotificationListenerService`: it reads the raw SMS text
straight from the OS, with no extra "Notification Access" permission grant and no risk of OEM
messaging apps formatting/truncating the notification text differently.

## The feature contract (read before touching TextFeaturizer.kt)

`TextFeaturizer` and `ml_pipeline/text_features.py` are two implementations of one contract. When
they drift, the model misbehaves on-device while every desktop metric still looks perfect — which
is precisely what happened in the previous version:

- The old classifier loaded a 20,823-word `tokenizer.json` and fed raw indices into a model whose
  embedding had 10,000 rows. TFLite threw `gather index out of bounds` on **21.5%** of real
  messages.
- Its `[^a-z0-9 ]` cleanup erased every Devanagari and Tamil character, so Hindi and Tamil SMS
  reached the model as empty strings and were undetectable by construction.

Both are structurally prevented now: features are hashed into a fixed range (so an out-of-range
id is impossible), there is no vocabulary asset to drift, and `normalize()` preserves letters,
combining marks and digits of every script.

`TextFeaturizerParityTest` asserts this file reproduces golden vectors from the Python side across
Devanagari, Tamil, Bengali, Arabic, emoji and non-BMP characters. Run it after any change:

```bash
./gradlew :app:testDebugUnitTest
```

Regenerate the fixture with `python ml_pipeline/export_parity_vectors.py` whenever the Python
featurizer changes. `SpamClassifier` additionally logs an error if `model_meta.json`'s
`num_buckets` / `max_features` disagree with this build's constants.

## Updating the model

Copy `ml_pipeline/models/spam_detector.tflite` and `model_meta.json` into `app/src/main/assets/`,
then re-run the parity test. `model_meta.json` carries the tuned decision threshold, so retraining
can retune it without an app code change. There is no `tokenizer.json` any more — deleting it
dropped the assets from 2.1 MB to 633 KB.
