# Hey Grok

A native Android starter (Kotlin, Jetpack Compose, minSdk 29, targetSdk 35) that listens
for **“Hey Grok”** / **“Hey Grok Bot”**, captures your question, and hands it to the
**Grok** (`ai.x.grok`) or **Grok Bot** (`ai.x.grok.bot`) app. If neither is installed,
it falls back to an xAI API answer screen. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Setup

1. Android Studio (Ladybug+) or JDK 17 + Android SDK 35.
2. `local.properties` in the repo root (gitignored) only needs `sdk.dir=/path/to/Android/sdk`.
   Android Studio writes it for you. No API keys go in the build.
3. `./gradlew installDebug`, open the app, grant mic + notifications, and turn on **Listening**.
   Say "Hey Grok" or "Hey Grok Bot".

The wake word runs fully on-device with **[openWakeWord](https://github.com/dscripka/openWakeWord)**
models on **ONNX Runtime Mobile**. No account or key is needed. The custom models were trained
from synthetic speech; see [MODELS.md](MODELS.md) for metrics, licenses, known limitations (synthetic-only training data) and how to retrain
(`training/run.sh`). Thresholds and "patience" (how many 80 ms frames a score must stay high) are configurable in `WakeDecider`.
The defaults (0.7 / 0.9, patience 5) give about 92 % / 99 % recall and 0–3 false accepts per hour on held-out synthetic speech.

## Appearance

The app follows the system theme. It goes light or dark with the system setting (`isSystemInDarkTheme()`).
On Android 12+ it uses **Material You dynamic color** (wallpaper-based), and on Android 10–11 it uses
branded fallback color schemes. A DayNight window theme keeps the splash screen and window background
in step, and the layout is edge-to-edge with system-bar icons that switch contrast automatically.

## Hand-off to Grok / Grok Bot

- When a question is captured, Hey Grok opens **Grok** (`ai.x.grok`) or **Grok Bot** (`ai.x.grok.bot`) if it's installed.
  The main screen and the answer screen show a **Get … on Google Play** button for each app that is missing
  (`market://details?id=…`, or the web Play listing if there's no store app).
- **Android can't detect whether you are signed in** to Grok or Grok Bot; apps don't expose that to other apps.
  If the target app is signed out, it simply shows its own login screen.
- Settings → **Always answer in this app** skips the hand-off and answers on Hey Grok's own screen.
  That needs your xAI API key (see below); without one, the screen explains that no answer is available.

## Optional: Grok Voice mode (paid, your own xAI key)

Open **Settings** in the app, paste your own [xAI API key](https://console.x.ai/), and pick a voice
(eve, ara, rex, sal, leo). The key is stored only on the device, encrypted with
`EncryptedSharedPreferences` under an Android Keystore master key. It's never put in BuildConfig or
`local.properties`, never logged, and backups are disabled.

When a key is set, the turn after the wake word connects to
`wss://api.x.ai/v1/realtime?model=grok-voice-latest` (xAI speech-to-speech, OpenAI Realtime-compatible)
over OkHttp WebSocket. It streams 24 kHz PCM16 mic audio, lets `server_vad` detect the end of
your question, and plays `response.output_audio.delta` through `AudioTrack`.

**This costs money: about $0.08 per minute of voice, billed to your own xAI account.** Remove the key
in Settings to go back to the free path: on-device speech-to-text, then the Grok / Grok Bot app.
The same key also powers the text fallback screen used when neither app is installed.

## Troubleshooting

Logs for the whole pipeline go to one tag:

```bash
adb logcat -s HeyGrok
```

You'll see `wake: score …` lines while scores are high, then `wake: DETECTED`, `wake: AudioRecord released`,
the recognizer callbacks (`stt[on-device]: onReadyForSpeech`, `onPartialResults`, `onResults` or `onError <code> (<name>)`),
and `router: …`. If the on-device recognizer fails (for example, the language pack isn't downloaded), the app
retries with the default recognizer. If nothing is heard within 12 s, it goes back to wake-word listening and
the error appears on the main screen.

On Pixels, speech recognition comes from Speech Services by Google / Android System Intelligence. If recognition never
starts, make sure that app is updated and the offline language pack for your language is downloaded.

## Status

| Piece | State |
|---|---|
| Foreground mic service + openWakeWord engine (ONNX Runtime) | Implemented, JVM-tested on synthetic clips |
| SpeechRecognizer capture | Implemented |
| App detection + ACTION_SEND / launch routing | Implemented (ACTION_SEND support in Grok apps not verified) |
| xAI text fallback (`XaiClient`) | Minimal: blocking, no streaming/history; uses the Settings key |
| Grok Voice mode (`GrokVoiceSession`) | Implemented, one turn per wake word; not yet tested against the live API |
| Default assistant role (VoiceInteractionService) | Documented only |

CI: `.github/workflows/android.yml` runs `./gradlew assembleDebug` on every push/PR.

## License

Hey Grok is licensed under the **[PolyForm Noncommercial License 1.0.0](LICENSE)**.
Copyright (c) 2026 Bradyn Glines.

In plain words: you're free to **use, modify, and share** this project for **noncommercial** purposes
(personal use, study, hobby projects, research, nonprofits). **No commercial use, and no selling it**,
including as part of a paid product or service. This summary isn't legal advice; the LICENSE text governs.

Third-party components keep their own licenses: the openWakeWord feature models are CC BY-NC-SA 4.0,
ONNX Runtime is MIT, other libraries are Apache-2.0, and the training voices require CC BY 4.0 attribution.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
