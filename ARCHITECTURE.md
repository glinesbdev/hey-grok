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
    AS[HeyGrokInteractionService<br/>ROLE_ASSISTANT] -->|long-press home / corner swipe| SES[HeyGrokSession]
    SES -->|startAssistantActivity| OV[AssistActivity<br/>translucent overlay]
    WID[Glance widget 🎙] --> OV
    TILE[Quick Settings tile] -->|role held: direct| SVC
    TILE -->|no role: startActivityAndCollapse| OV
    OV -->|MicCoordinator pauses wake word| QF[QuestionFlow<br/>STT or Grok Voice → AppRouter]
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

## Default assistant role, tile and widget

- **Assistant role.** `HeyGrokInteractionService` (`VoiceInteractionService`) plus `HeyGrokSessionService`/`HeyGrokSession`,
  declared through `res/xml/interaction_service.xml`. The user grants it in *Default apps → Digital assistant app*.
  `ROLE_ASSISTANT` isn't requestable through the role dialog, so we try `RoleManager.createRequestRoleIntent` and then fall back
  to the system settings screen. `onShow` launches `AssistActivity` with `startAssistantActivity`, and `AssistActivity` runs
  the same `QuestionFlow` the wake word uses.
- **Why the role matters for the wake word.** While we hold it, the system keeps our `VoiceInteractionService` bound. A
  system-bound VoiceInteractionService is exempt from background-activity-start limits, so `AppRouter` can open Grok / Grok Bot
  after a wake word with the app closed. It's also exempt from while-in-use limits, so the tile can start the mic service
  directly. Without the role everything still works; the hand-off just needs Hey Grok to be visible (or uses its own answer screen).
- **Recognition service.** A `VoiceInteractionService` must declare one, and the system may make it the device default.
  `HeyGrokRecognitionService` is therefore a transparent proxy to Google's recognizer, and our own `SpeechCapture` always
  targets an external recognizer explicitly.
- **One mic at a time.** `MicCoordinator` lets the overlay, widget or tile pause the wake-word engine (releasing `AudioRecord`),
  run one question, and resume it.
- **Tile.** `ListeningTileService` toggles the service. Tile clicks aren't a while-in-use exemption, so without the role it
  starts the service through the translucent activity (`startActivityAndCollapse`, using `unlockAndRun` when the phone is locked).
- **Widget.** Glance `HeyGrokWidget`. The toggle uses `actionStartService` (widget interactions are a while-in-use exemption),
  and the mic button opens the overlay. `WidgetUpdater` refreshes the widget and the tile whenever listening starts or stops.
