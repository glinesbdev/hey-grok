# Hey Grok

A native Android starter (Kotlin, Jetpack Compose, minSdk 29, targetSdk 35) that listens
for **“Hey Grok”** / **“Hey Grok Bot”**, captures your question, and hands it to the
**Grok** (`ai.x.grok`) or **Grok Bot** (`ai.x.grok.bot`) app. If neither is installed,
it falls back to an xAI API answer screen. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Setup

1. Android Studio (Ladybug+) or JDK 17 + Android SDK 35.
2. Optional `local.properties` in the repo root (gitignored, never commit it):
   ```properties
   sdk.dir=/path/to/Android/sdk
   XAI_API_KEY=your-xai-api-key   # optional, fallback answers only
   ```
3. `./gradlew installDebug`, open the app, grant mic + notifications, and turn on **Listening**.
   Say "Hey Grok" or "Hey Grok Bot".

The wake word runs fully on-device with **[openWakeWord](https://github.com/dscripka/openWakeWord)**
models on **ONNX Runtime Mobile**. No account or key is needed. The custom models were trained
from synthetic speech; see [MODELS.md](MODELS.md) for metrics, licenses, known limitations (high false-accept rate on continuous speech in v1) and how to retrain
(`training/run.sh`). The detection threshold is configurable in `OpenWakeWordEngine`
(default 0.5; raise it to reduce false accepts).

## Status

| Piece | State |
|---|---|
| Foreground mic service + openWakeWord engine (ONNX Runtime) | Implemented, JVM-tested on synthetic clips |
| SpeechRecognizer capture | Implemented |
| App detection + ACTION_SEND / launch routing | Implemented (ACTION_SEND support in Grok apps not verified) |
| xAI fallback (`XaiClient`) | Minimal: blocking, no streaming/history |
| Default assistant role (VoiceInteractionService) | Documented only |

CI: `.github/workflows/android.yml` runs `./gradlew assembleDebug` on every push/PR.

## Third-party components & attribution

- openWakeWord (Apache-2.0) by David Scripka: `melspectrogram.onnx`, `embedding_model.onnx`
  (re-implementation of Google's Apache-2.0 `speech_embedding`), plus the training approach. See MODELS.md for a license caveat.
- ONNX Runtime (MIT) by Microsoft.
- Training data was synthesized with Piper (GPL-3.0, tool only, not shipped) using voices trained on
  LibriTTS-R (CC BY 4.0, Koizumi et al.) and VCTK (CC BY 4.0, University of Edinburgh CSTR).
