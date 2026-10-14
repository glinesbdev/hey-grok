package dev.glinesb.heygrok.assist

import android.content.Context
import dev.glinesb.heygrok.AppState
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.router.AppRouter
import dev.glinesb.heygrok.settings.SecureSettings
import dev.glinesb.heygrok.speech.SpeechCapture
import dev.glinesb.heygrok.voice.GrokVoiceSession
import dev.glinesb.heygrok.wake.WakePhrase

/**
 * One question, end to end, shared by every entry point (wake word, assistant overlay, tile, widget):
 * with a saved xAI key → one Grok Voice turn; otherwise → SpeechRecognizer, then AppRouter.
 * Caller must make sure the mic is free first (see [MicCoordinator]). Updates [AppState] as it goes.
 */
class QuestionFlow(private val context: Context, private val source: String) {
    private val settings = SecureSettings(context)
    private val speech = SpeechCapture(context)
    private var voice: GrokVoiceSession? = null

    /**
     * [onDone] is called exactly once, on any thread, when the turn has finished.
     * With [deliver], the transcript is handed to the caller (which routes and finishes) instead of AppRouter;
     * in that case [onDone] is only called for errors and Grok Voice turns.
     */
    fun run(phrase: WakePhrase, onDone: () -> Unit, deliver: ((String) -> Unit)? = null) {
        val key = settings.apiKey
        HgLog.i("flow[$source]: start ($phrase, mode=${if (key != null) "grok-voice" else "stt"})")
        AppState.update { it.copy(capturing = true) }
        val done = { AppState.update { it.copy(capturing = false) }; onDone() }
        if (key != null) { runVoice(key, done); return }
        AppState.update { it.copy(status = "Starting speech recognizer…", partial = null, sendingTo = null) }
        speech.capture(object : SpeechCapture.Callback {
            override fun onListening(engine: String) =
                AppState.update { it.copy(status = "Speak your question ($engine recognizer)…") }
            override fun onPartial(text: String) = AppState.update { it.copy(partial = text) }
            override fun onRms(db: Float) = AppState.update { it.copy(rms = db) }
            override fun onResult(text: String) {
                AppState.update { it.copy(capturing = false) }
                if (deliver != null) { HgLog.i("flow[$source]: transcript='$text' → caller"); deliver(text); return }
                HgLog.i("flow[$source]: question='$text' → routing")
                AppState.update { it.copy(lastQuestion = text, partial = null, status = "Routing…") }
                AppRouter(context).route(text, phrase)
                done()
            }
            override fun onError(message: String) {
                HgLog.w("flow[$source]: speech failed: $message")
                AppState.update { it.copy(status = "$message — try again", partial = null) }
                done()
            }
        })
    }

    private fun runVoice(key: String, onDone: () -> Unit) {
        AppState.update { it.copy(status = "Connecting to Grok Voice…", lastQuestion = "(voice)", lastResult = null) }
        voice = GrokVoiceSession(key, settings.voice, object : GrokVoiceSession.Listener {
            override fun onStatus(text: String) = AppState.update { it.copy(status = text) }
            override fun onUserTranscript(text: String) = AppState.update { it.copy(lastQuestion = text) }
            override fun onAssistantTranscript(text: String) = AppState.update { it.copy(lastResult = text) }
            override fun onFinished(error: String?) {
                voice = null
                HgLog.i("flow[$source]: grok voice finished${error?.let { " ($it)" } ?: ""}")
                if (error != null) AppState.update { it.copy(status = error) }
                onDone()
            }
        }).also { it.start() }
    }

    fun cancel() { speech.cancel(); voice?.cancel(); voice = null; AppState.update { it.copy(capturing = false) } }
}

/**
 * Lets a one-shot capture (assistant overlay, widget, tile) borrow the mic from the running
 * wake-word service and give it back afterwards. Same process, so a simple registry is enough.
 */
object MicCoordinator {
    interface Owner { fun pauseForCapture(); fun resumeAfterCapture() }
    @Volatile var owner: Owner? = null

    fun borrow() { owner?.let { HgLog.d("mic: pausing wake word for one-shot capture"); it.pauseForCapture() } }
    fun giveBack() { owner?.let { HgLog.d("mic: resuming wake word"); it.resumeAfterCapture() } }
}
