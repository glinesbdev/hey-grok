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

## Ways to ask (Pixel / Android 10+)

| Entry point | How to enable | Notes |
|---|---|---|
| **"Hey Grok" wake word** | Main screen → turn on **Listening** | Mic foreground service with a persistent notification. |
| **Default digital assistant** (long-press the home bar / swipe up from a bottom corner) | Main screen or Settings → **Set as default assistant**. Android opens *Settings → Apps → Default apps → Digital assistant app*; pick **Hey Grok** | Opens a translucent Hey Grok overlay that listens for your question. While Hey Grok holds this role, Android also lets the wake-word hand-off open Grok / Grok Bot **with the app closed**. Everything else works without the role. |
| **Quick Settings tile** | Pull down Quick Settings twice → ✏️ **Edit** → drag **Hey Grok** into your tiles | Tap to turn the wake word on or off; the tile shows its state. Without the assistant role, turning it *on* briefly flashes the overlay (Android requires a visible screen to start mic access from a tile). |
| **Home-screen widget** | Long-press the home screen → **Widgets** → **Hey Grok** → drag it out | 🎙 asks one question right away (opens the overlay). **On/Off** toggles the wake word. Follows Material You colors and light/dark. |

### Which app gets the question

- **"Hey Grok" / "Hey Grok Bot"** always go to Grok / Grok Bot respectively.
- **Buttons and gestures** (widget 🎙, Quick Settings, assistant long-press / corner swipe) use **Settings → Default app for button/assistant launches**: *Grok*, *Grok Bot* or *Ask each time*. It defaults to *Ask each time*, or to *Grok Bot* if that's the only app installed.
- **The overlay shows Grok / Grok Bot chips** for the apps that are installed, plus *Get …* Play Store chips for any that aren't. Tap one before or while speaking. With *Ask each time*, the chips are large, and after you finish speaking the overlay waits 8 s for a tap before falling back to the first installed app.
- **Starting the question with "Grok bot, …" or "Grok, …"** overrides everything. The prefix is removed before sending.
- **The widget's Bot button** (shown when Grok Bot is installed) always asks Grok Bot.

### Messaging a specific Grok Bot by voice

Say **"Hey Grok Bot, tell Android Dev the build worked"**. The verbs *tell / ask / message / text / ping* all work. Hey Grok then opens that Bot's chat in the Grok Bot app and copies "the build worked" to the clipboard ("Message copied, paste to send"). **Nothing is ever sent for you.**

- **Setup:** add Bots under **Settings → Grok Bot contacts**: the name you'll say, plus the agent link or id.
  - On desktop, copy the Bot's link (`grokbot://app/v1/agent?id=…`); pasting the full link is fine.
  - I haven't found a way to see an agent id in the Android app.
- **Matching** is loose: case, punctuation, "android-dev" vs "androiddev", and small mis-hearings.
- **This also works** from the overlay when you start with "Grok bot, tell …".
- **It doesn't apply in Grok Voice mode** (a saved xAI key), because that mode never produces a transcript to route.

What Grok Bot for Android (1.14.1) accepts, read from its `AndroidManifest.xml`:
- `VIEW` on the `grokbot:` scheme (any host or path);
- verified app links for `https://x.ai/bot/agent/…`, `/bot/plugin/…` and `/bot/<21-char template id>`;
- `SEND` with `text/plain`.

No parameter for prefilled text is documented, which is why the message goes on the clipboard. If the agent link doesn't open, Hey Grok falls back to sharing just the message into Grok Bot with `SEND`.

**Android restrictions this works around** (from the official Android docs):
- Only widget and notification interactions, or an app providing the `VoiceInteractionService`, may start a **microphone** service without a visible screen. Quick Settings tile clicks may not.
- Starting another app's activity from the background is blocked unless the system binds one of our services, such as the assistant's `VoiceInteractionService`.

When Hey Grok becomes the assistant, Android may also route other apps' voice typing through Hey Grok's recognition service. That service is a pass-through to Google's recognizer, so voice input elsewhere keeps working.

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
Other tags in the same log: `assist:` (assistant session), `overlay:`, `tile:`, `widget:`, `role:`, `mic:`, `settings: launch target`, `router: bot message` (wake word paused/resumed for a one-shot question) and `recog-proxy:`.

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
| Default assistant role (VoiceInteractionService + session overlay) | Implemented; untested on device |
| Quick Settings tile, Glance home-screen widget | Implemented; untested on device |

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
