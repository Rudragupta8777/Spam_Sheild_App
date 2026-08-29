# Spam Shield

On-device SMS spam classifier for code-mixed (Hinglish/Tanglish) messages. No internet needed to
screen a message — a `BroadcastReceiver` runs the bundled `spam_detector.tflite` locally on every
incoming SMS. Only spam is ever reported to the telemetry backend, and only as text; safe messages
never leave the device.

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
adb:

```bash
adb shell am broadcast -a com.spamshield.app.DEBUG_TEST_SMS \
  --es sender "VM-ALERTS" \
  --es body "Bhai lottery jeet gaya, turant click karo: bit.ly/xyz"
```

Check the app's message list, or `adb logcat -s SpamShield:* Telemetry:*`.

## Architecture

| File | Role |
|---|---|
| `SmsReceiver.kt` | `SMS_RECEIVED` broadcast receiver — classifies, persists, reports spam |
| `SpamClassifier.kt` | Loads `spam_detector.tflite` + `tokenizer.json`, runs inference |
| `data/MessageDbHelper.kt` / `MessageRepository.kt` | Local SQLite history + reactive `LiveData` |
| `MainActivity.kt` / `MessageAdapter.kt` | Message list UI, manual test dialog, correction dialog |
| `TelemetryClient.kt` | Reports to the backend (spam text, or a correction) |
| `TelemetrySyncWorker.kt` | Periodic retry for reports that failed to send immediately |

Why a `BroadcastReceiver` and not a `NotificationListenerService`: it reads the raw SMS text
straight from the OS, with no extra "Notification Access" permission grant and no risk of OEM
messaging apps formatting/truncating the notification text differently.
