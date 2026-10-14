# Architecture

```mermaid
flowchart TD
    UI[MainActivity<br/>permissions + toggle] -->|startForegroundService<br/>while visible| SVC
    subgraph SVC[WakeWordService · FGS type=microphone]
        WW[OpenWakeWordEngine<br/>AudioRecord 16 kHz → melspec → embedding → keyword ONNX<br/>"Hey Grok" / "Hey Grok Bot"]
    end
    WW -->|wake, no key: free| STT[SpeechCapture<br/>Android SpeechRecognizer]
    WW -->|wake, user key set: paid| GV[GrokVoiceSession<br/>OkHttp WSS api.x.ai/v1/realtime<br/>PCM16 24 kHz + server_vad → AudioTrack]
    GV -.->|turn done| WW
    STT -->|question text| R[AppRouter]
    R -->|PackageManager + &lt;queries&gt;| D{Installed?}
    D -->|Grok ai.x.grok| G[ACTION_SEND text<br/>or launch intent]
    D -->|Grok Bot ai.x.grok.bot| B[ACTION_SEND text<br/>or launch intent]
    D -->|neither| F[ResultActivity overlay<br/>XaiClient → api.x.ai]
    STT -.->|done| WW
    AS[Optional: VoiceInteractionService<br/>RoleManager.ROLE_ASSISTANT] -.->|long-press home / corner swipe| STT
```

## Flow

1. **Foreground mic service.** `WakeWordService` is a foreground service with
   `foregroundServiceType="microphone"` and a persistent notification. On Android 14+
   a microphone FGS can only be started while the app is visible and `RECORD_AUDIO`
   is granted, so the user starts it from `MainActivity`. It is `START_NOT_STICKY`
   on purpose, because a background restart would break that rule.
2. **On-device wake word.** `WakeWordEngine` is an interface. `OpenWakeWordEngine` reads 16 kHz
   mono PCM with `AudioRecord` and feeds 80 ms frames to `OwwPipeline`, a Kotlin port of
   openWakeWord's streaming features: `melspectrogram.onnx` → `embedding_model.onnx` (96-d every
   80 ms) → the last 16 embeddings go to the `hey_grok.onnx` and `hey_grok_bot.onnx` classifiers on
   ONNX Runtime Mobile. "Hey grok" is a prefix of "hey grok bot", so `WakeDecider` waits about
   640 ms after "hey grok" fires to see whether the bot model fires too. No audio leaves the device
   until a wake word is detected. Models and metrics are in MODELS.md.
3. **Speech-to-text.** On wake, the engine releases the mic and `SpeechCapture` runs
   `SpeechRecognizer` (on-device when available, API 31+) once to get the question.
   Then wake-word listening resumes.
4. **Router.** `AppRouter` prefers the app named by the phrase and falls back to the other.
   It tries `ACTION_SEND` (text/plain) aimed at that package, then the plain launch intent.
5. **Fallback.** If neither app is installed (or neither accepts the intent),
   `ResultActivity` asks the xAI API through `XaiClient` and shows the answer.

6. **Optional Grok Voice.** If the user saved their own xAI key in Settings (EncryptedSharedPreferences +
   Keystore), the wake word starts a speech-to-speech turn over the realtime WebSocket instead of
   STT and routing. That turn is paid (about $0.08/min) and billed to the user's own xAI account. A
   production app should mint short-lived ephemeral tokens on a backend instead of holding a long-lived key.

## Platform limits

- **System DSP hotword is privileged-only.** `AlwaysOnHotwordDetector` and
  `HotwordDetectionService` (the low-power path “Hey Google” uses) need
  `CAPTURE_AUDIO_HOTWORD` / `MANAGE_HOTWORD_DETECTION`, which are signature/privileged
  permissions. A Play Store app can’t use them, which is why we run openWakeWord on the CPU
  in a foreground service. Expect more battery use than with “Hey Google”.
- **Background activity starts.** Android 10+ blocks a service from starting an
  activity in the background, and having an FGS doesn’t exempt you. Handing off works
  reliably while our UI is visible or while we hold the assistant role. Other options are
  a full-screen-intent / heads-up notification the user taps, or the
  `SYSTEM_ALERT_WINDOW` permission.
- **Passing the question in.** Neither Grok app documents a deep link that accepts a
  query. `ACTION_SEND` support is untested; otherwise we only open the app.

## Optional: default assistant role

Use a `VoiceInteractionService` + `VoiceInteractionSessionService` (declared with
`android.permission.BIND_VOICE_INTERACTION` and an `<voice-interaction-service>` XML)
and ask for `RoleManager.ROLE_ASSISTANT` via `roleManager.createRequestRoleIntent(...)`.
This lets long-press home or the corner swipe open our session directly (no wake word),
and it also helps with background-start limits. It's not implemented yet.
