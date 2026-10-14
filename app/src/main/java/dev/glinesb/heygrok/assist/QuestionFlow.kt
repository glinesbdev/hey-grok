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
 * Caller must make sure the mic is free first (see [MicCoordinator]).
 *
 * Each [run] opens a new AppState session id; all state writes and callbacks are scoped to it, and
 * [reset] (the single cancel/teardown path) makes anything that arrives later a no-op.
 */
class QuestionFlow(private val context: Context, private val source: String) {
    private val settings = SecureSettings(context)
    private val speech = SpeechCapture(context)
    private var voice: GrokVoiceSession? = null
    @Volatile private var sid = 0L
    @Volatile private var active = false
    private var onDone: (() -> Unit)? = null

    /** Session id of the running question (0 if none). */
    val sessionId: Long get() = if (active) sid else 0L

    /**
     * [onDone] is called at most once, on any thread, when the turn has finished (not after [reset]).
     * With [deliver], the transcript is handed to the caller (which routes and finishes) instead of AppRouter;
     * in that case [onDone] is only called for errors and Grok Voice turns.
     */
    fun run(phrase: WakePhrase, onDone: () -> Unit, deliver: ((String) -> Unit)? = null): Long {
        if (active) reset("superseded")
        val id = AppState.beginSession()
        sid = id; active = true; this.onDone = onDone
        val key = settings.apiKey
        HgLog.i("flow[$source] s$id: start ($phrase, mode=${if (key != null) "grok-voice" else "stt"})")
        if (key != null) { runVoice(id, key); return id }
        AppState.updateIf(id) { it.copy(status = "Starting speech recognizer…") }
        speech.capture(id, object : SpeechCapture.Callback {
            override fun onListening(engine: String) { if (live(id)) AppState.updateIf(id) { it.copy(status = "Speak your question ($engine recognizer)…") } }
            override fun onPartial(text: String) { if (live(id)) AppState.updateIf(id) { it.copy(partial = text) } }
            override fun onRms(db: Float) { if (live(id)) AppState.updateIf(id) { it.copy(rms = db) } }
            override fun onResult(text: String) {
                if (!live(id)) { HgLog.w("flow[$source] s$id: dropped stale result"); return }
                AppState.updateIf(id) { it.copy(capturing = false, partial = null) }
                if (deliver != null) { HgLog.i("flow[$source] s$id: transcript='$text' → caller"); active = false; this@QuestionFlow.onDone = null; deliver(text); return }
                HgLog.i("flow[$source] s$id: question='$text' → routing")
                AppState.updateIf(id) { it.copy(lastQuestion = text, status = "Routing…") }
                AppRouter(context).route(text, phrase, id)
                finish(id)
            }
            override fun onError(message: String) {
                if (!live(id)) return
                HgLog.w("flow[$source] s$id: speech failed: $message")
                AppState.updateIf(id) { it.copy(status = "$message — try again") }
                finish(id)
            }
        })
        return id
    }

    private fun live(id: Long) = active && sid == id

    private fun finish(id: Long) {
        if (!live(id)) return
        active = false
        AppState.endSession(id)
        val d = onDone; onDone = null
        HgLog.d("flow[$source] s$id: done")
        d?.invoke()
    }

    private fun runVoice(id: Long, key: String) {
        AppState.updateIf(id) { it.copy(status = "Connecting to Grok Voice…", lastQuestion = "(voice)") }
        voice = GrokVoiceSession(key, settings.voice, object : GrokVoiceSession.Listener {
            override fun onStatus(text: String) { if (live(id)) AppState.updateIf(id) { it.copy(status = text) } }
            override fun onUserTranscript(text: String) { if (live(id)) AppState.updateIf(id) { it.copy(lastQuestion = text) } }
            override fun onAssistantTranscript(text: String) { if (live(id)) AppState.updateIf(id) { it.copy(lastResult = text) } }
            override fun onFinished(error: String?) {
                if (!live(id)) return
                voice = null
                HgLog.i("flow[$source] s$id: grok voice finished${error?.let { " ($it)" } ?: ""}")
                if (error != null) AppState.updateIf(id) { it.copy(status = error) }
                finish(id)
            }
        }).also { it.start() }
    }

    /**
     * The single teardown for every cancel/dismiss path (overlay dismiss, back, tap outside, pop-up tap,
     * timeouts, superseded by a new wake). Stops STT / Grok Voice, ends the session in AppState and makes
     * every late callback a no-op. Does not call onDone. Safe to call repeatedly.
     */
    fun reset(reason: String) {
        val id = sid
        if (active) HgLog.i("flow[$source] s$id: reset ($reason)")
        active = false; onDone = null
        speech.cancel()
        voice?.cancel(); voice = null
        if (id != 0L) AppState.endSession(id)
    }
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
