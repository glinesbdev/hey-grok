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
from synthetic speech; see [MODELS.md](MODELS.md) for metrics, licenses, known limitations (high false-accept rate on continuous speech in v1) and how to retrain
(`training/run.sh`). The detection threshold is configurable in `OpenWakeWordEngine`
(default 0.5; raise it to reduce false accepts).

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

## Third-party components & attribution

- openWakeWord (Apache-2.0) by David Scripka: `melspectrogram.onnx`, `embedding_model.onnx`
  (re-implementation of Google's Apache-2.0 `speech_embedding`), plus the training approach. See MODELS.md for a license caveat.
- ONNX Runtime (MIT) by Microsoft.
- Training data was synthesized with Piper (GPL-3.0, tool only, not shipped) using voices trained on
  LibriTTS-R (CC BY 4.0, Koizumi et al.) and VCTK (CC BY 4.0, University of Edinburgh CSTR).
