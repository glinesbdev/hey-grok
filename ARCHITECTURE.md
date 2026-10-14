# Architecture

```mermaid
flowchart TD
    UI[MainActivity<br/>permissions + toggle] -->|startForegroundService<br/>while visible| SVC
    subgraph SVC[WakeWordService · FGS type=microphone]
        WW[WakeWordEngine<br/>Porcupine on-device<br/>"Hey Grok" / "Hey Grok Bot"]
    end
    WW -->|wake + phrase| STT[SpeechCapture<br/>Android SpeechRecognizer]
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
2. **On-device wake word.** `WakeWordEngine` is an interface. `PorcupineWakeWordEngine`
   loads custom `.ppn` models (“Hey Grok”, “Hey Grok Bot”). No audio leaves the device
   until the wake word is detected. openWakeWord could be dropped in later as a free alternative.
3. **Speech-to-text.** On wake, the engine releases the mic and `SpeechCapture` runs
   `SpeechRecognizer` (on-device when available, API 31+) once to get the question.
   Then wake-word listening resumes.
4. **Router.** `AppRouter` prefers the app named by the phrase and falls back to the other.
   It tries `ACTION_SEND` (text/plain) aimed at that package, then the plain launch intent.
5. **Fallback.** If neither app is installed (or neither accepts the intent),
   `ResultActivity` asks the xAI API through `XaiClient` and shows the answer.

## Platform limits

- **System DSP hotword is privileged-only.** `AlwaysOnHotwordDetector` and
  `HotwordDetectionService` (the low-power path “Hey Google” uses) need
  `CAPTURE_AUDIO_HOTWORD` / `MANAGE_HOTWORD_DETECTION`, which are signature/privileged
  permissions. A Play Store app can’t use them, which is why we run Porcupine on the CPU
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
