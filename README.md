# Hey Grok

A native Android starter (Kotlin, Jetpack Compose, minSdk 29, targetSdk 35) that listens
for **“Hey Grok”** / **“Hey Grok Bot”**, captures your question, and hands it to the
**Grok** (`ai.x.grok`) or **Grok Bot** (`ai.x.grok.bot`) app. If neither is installed,
it falls back to an xAI API answer screen. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Setup

1. Android Studio (Ladybug+) or JDK 17 + Android SDK 35.
2. Create `local.properties` in the repo root (it's gitignored, so never commit it):
   ```properties
   sdk.dir=/path/to/Android/sdk
   PICOVOICE_ACCESS_KEY=your-picovoice-access-key
   XAI_API_KEY=your-xai-api-key   # optional, fallback answers only
   ```
3. **Train the wake words** at [console.picovoice.ai](https://console.picovoice.ai/):
   sign up (free tier) → copy your AccessKey → *Porcupine* → enter `Hey Grok` →
   platform **Android** → *Train* → download. Do the same for `Hey Grok Bot`. Rename them to
   `hey-grok_android.ppn` and `hey-grok-bot_android.ppn` and put them in
   `app/src/main/assets/`. (`*.ppn` is gitignored because the models are tied to your key.)
   Without them, the built-in keyword **“Computer”** works for testing.
4. `./gradlew installDebug`, open the app, grant mic + notifications, and turn on **Listening**.

## Status

| Piece | State |
|---|---|
| Foreground mic service + Porcupine engine | Implemented |
| SpeechRecognizer capture | Implemented |
| App detection + ACTION_SEND / launch routing | Implemented (ACTION_SEND support in Grok apps not verified) |
| xAI fallback (`XaiClient`) | Minimal: blocking, no streaming/history |
| Default assistant role (VoiceInteractionService) | Documented only |

CI: `.github/workflows/android.yml` runs `./gradlew assembleDebug` on every push/PR.
