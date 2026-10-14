package dev.glinesb.heygrok.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.SystemClock
import dev.glinesb.heygrok.HgLog
import java.util.Locale

/**
 * Question capture with Android's SpeechRecognizer. One *utterance* may span several recognizer
 * *segments*: Google often ends a segment on a short pause, so we restart and append until there
 * has been [trueSilenceMs] of real silence.
 *
 * Timers (all on the main looper, all logged under "HeyGrok"):
 *  - no-speech: [noSpeechMs] (8 s) from the first onReadyForSpeech, only until speech begins. If the
 *    recognizer gives up earlier on its own (ERROR_SPEECH_TIMEOUT / NO_MATCH), it is silently restarted.
 *  - watchdog: [watchdogMs] (60 s) since the *last* speech evidence (partial change or RMS ≥ [SPEECH_RMS_DB]),
 *    reset on every bit of speech; guards against a stuck recognizer.
 *  - absolute: [maxTotalMs] (3 min) ceiling for the whole capture.
 *  - continuation: after an auto-restart, the utterance ends once there's been [trueSilenceMs] of
 *    silence with no new partials (longer if the text so far ends in "and", "the", "to", …).
 * End of each segment is driven by the recognizer (onEndOfSpeech → onResults); we ask for longer
 * silence tolerance through the EXTRA_SPEECH_INPUT_* extras, which some recognizers ignore.
 * Waits [startDelayMs] before starting so the wake-word AudioRecord has released the mic.
 * Tries the on-device recognizer first (Android 12+), falling back to the default one on errors.
 */
class SpeechCapture(
    private val context: Context,
    private val startDelayMs: Long = 350,
    private val noSpeechMs: Long = 8_000,
    private val watchdogMs: Long = 60_000,
    private val maxTotalMs: Long = 180_000,
    private val trueSilenceMs: Long = 1_800,
    private val maxSegments: Int = 8,
) {
    interface Callback {
        fun onListening(engine: String) {}
        fun onPartial(text: String) {}
        fun onRms(db: Float) {}
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session = 0 // ignores late callbacks from a destroyed recognizer

    // per-capture state (main thread only)
    private var cb: Callback? = null
    private val committed = StringBuilder()
    private var segPartial = ""
    private var speechStarted = false
    private var lastSpeechAt = 0L
    private var lastResetLog = 0L
    private var segments = 0
    private var continuation = false
    private var firstReadyAt = 0L
    /** First continuation wait = what's left of [trueSilenceMs] after the silence already seen. */
    private var firstGrace: Long? = null
    private var finished = true

    private val noSpeechTimer = Runnable {
        if (!speechStarted) { HgLog.w("stt: no speech within ${noSpeechMs}ms → giving up"); finish(null, "No speech heard (timeout)") }
    }
    private val watchdog = Runnable { HgLog.w("stt: watchdog — no speech for ${watchdogMs}ms"); finish(text(), "Speech stopped responding") }
    private val absolute = Runnable { HgLog.w("stt: absolute cap ${maxTotalMs}ms reached"); finish(text(), "Question too long") }
    private val continuationTimer = Runnable {
        HgLog.i("stt: ${trueSilenceMs}ms of true silence after restart → utterance complete")
        finish(text(), "Didn't catch that")
    }

    fun capture(callback: Callback) {
        main.post {
            destroyNow()
            cb = callback; committed.clear(); segPartial = ""; speechStarted = false; lastSpeechAt = 0
            segments = 0; continuation = false; firstReadyAt = 0; finished = false
            main.postDelayed(absolute, maxTotalMs)
            main.postDelayed({ start(preferOnDevice = true) }, startDelayMs)
        }
    }

    /** Cancel any session. Safe from any thread; destroys synchronously when already on main. */
    fun cancel() {
        if (Looper.myLooper() == Looper.getMainLooper()) { finished = true; destroyNow() }
        else main.post { finished = true; destroyNow() }
    }

    private fun text() = (committed.toString() + " " + segPartial).trim()

    private fun destroyNow() {
        main.removeCallbacksAndMessages(null)
        session++
        recognizer?.let { HgLog.d("stt: destroy"); runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    private fun finish(text: String?, errorIfEmpty: String) {
        if (finished) return
        finished = true
        destroyNow()
        val c = cb ?: return
        if (!text.isNullOrBlank()) { HgLog.i("stt: utterance done (${segments} segment(s)) '$text'"); c.onResult(text) }
        else c.onError(errorIfEmpty)
    }

    /** Partial change or loud-enough RMS: speech is happening, push every inactivity timer back. */
    private fun speechEvidence(kind: String) {
        val now = SystemClock.uptimeMillis()
        lastSpeechAt = now
        if (!speechStarted) {
            speechStarted = true; main.removeCallbacks(noSpeechTimer)
            HgLog.i("stt: speech began ($kind) → no-speech timer off")
        }
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, watchdogMs)
        if (continuation) { main.removeCallbacks(continuationTimer); main.postDelayed(continuationTimer, graceMs()) }
        if (now - lastResetLog > 1_000) { lastResetLog = now; HgLog.d("stt: timers reset by $kind") }
    }

    private fun graceMs() = if (looksIncomplete(text())) trueSilenceMs + 1_500 else trueSilenceMs

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Longer pause tolerance; best effort — Google's recognizers may ignore these.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2_000L)
    }

    private fun start(preferOnDevice: Boolean) {
        if (finished) return
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }; recognizer = null; session++
        val c = cb ?: return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            HgLog.w("stt: no recognition service available"); finish(null, "No speech recognizer on this device"); return
        }
        val onDevice = preferOnDevice && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val engine = if (onDevice) "on-device" else "default"
        val r = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else Recognizers.external(context)?.let { HgLog.d("stt: default → $it"); SpeechRecognizer.createSpeechRecognizer(context, it) }
                ?: SpeechRecognizer.createSpeechRecognizer(context) // never our own proxy if a real one exists
        } catch (e: Exception) {
            HgLog.e("stt: create $engine failed", e)
            if (onDevice) { start(preferOnDevice = false); return }
            finish(null, "Recognizer unavailable: ${e.message}"); return
        }
        recognizer = r
        val my = session
        var segDone = false

        fun restartSegment(reason: String) {
            segments++
            HgLog.i("stt: restart #$segments ($reason)")
            segPartial = ""; segDone = false
            main.postDelayed({
                if (finished || my != session) return@postDelayed
                try { r.startListening(intent()) } catch (e: Exception) {
                    HgLog.e("stt: restart failed", e); finish(text(), "Couldn't keep listening")
                }
            }, 60)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (my != session) return
                HgLog.d("stt[$engine]: onReadyForSpeech (segment ${segments + 1}, continuation=$continuation)")
                if (firstReadyAt == 0L) {
                    firstReadyAt = SystemClock.uptimeMillis()
                    main.postDelayed(noSpeechTimer, noSpeechMs)
                    c.onListening(engine)
                }
                if (continuation) {
                    val g = firstGrace ?: graceMs(); firstGrace = null
                    HgLog.d("stt: continuation window ${g}ms")
                    main.removeCallbacks(continuationTimer); main.postDelayed(continuationTimer, g)
                }
            }
            override fun onBeginningOfSpeech() { HgLog.d("stt[$engine]: onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {
                if (my != session) return
                c.onRms(rmsdB)
                if (rmsdB >= SPEECH_RMS_DB) speechEvidence("rms")
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { HgLog.d("stt[$engine]: onEndOfSpeech") }
            override fun onPartialResults(partialResults: Bundle?) {
                if (my != session) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                HgLog.d("stt[$engine]: onPartialResults '$t'")
                if (t.isNotBlank() && t != segPartial) {
                    segPartial = t
                    speechEvidence("partial")
                    c.onPartial(text())
                }
            }
            override fun onResults(results: Bundle?) {
                if (my != session || segDone) return
                segDone = true
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { segPartial }
                HgLog.i("stt[$engine]: onResults '$t'")
                if (t.isNotBlank()) { if (committed.isNotEmpty()) committed.append(' '); committed.append(t.trim()) }
                segPartial = ""
                val silence = SystemClock.uptimeMillis() - lastSpeechAt
                val incomplete = looksIncomplete(committed.toString())
                if (committed.isNotBlank() && segments + 1 < maxSegments && (silence < trueSilenceMs || incomplete)) {
                    continuation = true
                    firstGrace = (graceMs() - silence).coerceAtLeast(900)
                    c.onPartial(committed.toString())
                    restartSegment("segment ended after ${silence}ms silence, incomplete=$incomplete")
                } else {
                    HgLog.d("stt: segment final, silence=${silence}ms → done")
                    finish(committed.toString(), "Didn't catch that")
                }
            }
            override fun onError(error: Int) {
                if (my != session || finished) return
                val name = errorName(error)
                HgLog.w("stt[$engine]: onError $error ($name), speechStarted=$speechStarted continuation=$continuation")
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                when {
                    // continuation segment heard nothing more → the utterance is complete
                    continuation -> finish(text(), "Didn't catch that")
                    // recognizer gave up before our 8 s no-speech window → keep listening
                    quiet && !speechStarted && SystemClock.uptimeMillis() - firstReadyAt < noSpeechMs - 500 ->
                        restartSegment("recognizer gave up early with no speech")
                    quiet && text().isNotBlank() -> finish(text(), "Didn't catch that")
                    // on-device failed for a non-user reason → default recognizer
                    onDevice && !quiet -> { HgLog.i("stt: falling back to default recognizer"); main.postDelayed({ start(preferOnDevice = false) }, startDelayMs) }
                    else -> finish(text(), "Speech error: $name")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) { HgLog.d("stt[$engine]: onEvent $eventType") }
        })
        HgLog.i("stt: startListening ($engine)")
        try {
            r.startListening(intent())
        } catch (e: Exception) {
            HgLog.e("stt: startListening failed ($engine)", e)
            if (onDevice) start(preferOnDevice = false) else finish(null, "Couldn't start listening")
        }
    }

    companion object {
        /** SpeechRecognizer rmsdB is roughly -2 (silence) … 10; above this counts as speech. */
        const val SPEECH_RMS_DB = 5f

        private val TRAILING = setOf("and", "or", "but", "so", "because", "the", "a", "an", "to", "of", "for", "with",
            "um", "uh", "like", "that", "if", "then", "my", "your", "is", "are", "about", "in", "on", "at")

        /** The text so far ends mid-sentence ("…and", "…the", trailing comma), so more is probably coming. */
        fun looksIncomplete(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            if (t.endsWith(",")) return true
            return t.split(Regex("""\s+""")).last().lowercase().trimEnd('.', '?', '!') in TRAILING
        }

        fun errorName(e: Int) = when (e) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_AUDIO -> "audio (mic busy?)"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no mic permission"
            10 -> "too many requests"
            11 -> "server disconnected"
            12 -> "language not supported"
            13 -> "language pack unavailable"
            14 -> "cannot check support"
            else -> "error $e"
        }
    }
}
