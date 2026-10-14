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
import dev.glinesb.heygrok.HgLog
import java.util.Locale

/**
 * One-shot question capture with Android's SpeechRecognizer.
 *
 * - Everything (create, start, callbacks, destroy) runs on the main looper, as SpeechRecognizer requires.
 * - Waits [startDelayMs] so the wake-word AudioRecord has fully released the mic.
 * - Tries the on-device recognizer first (Android 12+, if available). On any error before a result
 *   (missing language pack, busy, client/server error) it retries once with the default recognizer.
 * - A hard timeout ends a stuck session so wake-word listening resumes.
 * - Partial results are reported for the UI. Every callback is logged under tag "HeyGrok".
 */
class SpeechCapture(
    private val context: Context,
    private val startDelayMs: Long = 350,
    private val timeoutMs: Long = 12_000,
) {
    interface Callback {
        fun onListening(engine: String) {}
        fun onPartial(text: String) {}
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session = 0 // ignores late callbacks from a destroyed recognizer

    fun capture(cb: Callback) {
        main.postDelayed({ start(cb, preferOnDevice = true) }, startDelayMs)
    }

    /** Cancel any session. Safe from any thread; destroys synchronously when already on main. */
    fun cancel() {
        if (Looper.myLooper() == Looper.getMainLooper()) destroyNow() else main.post { destroyNow() }
    }

    private fun destroyNow() {
        main.removeCallbacksAndMessages(null)
        session++
        recognizer?.let { HgLog.d("stt: destroy"); runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    private fun start(cb: Callback, preferOnDevice: Boolean) {
        destroyNow() // synchronous: never destroys the recognizer we are about to create
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            HgLog.w("stt: no recognition service available"); cb.onError("No speech recognizer on this device"); return
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
            if (onDevice) { start(cb, preferOnDevice = false); return }
            cb.onError("Recognizer unavailable: ${e.message}"); return
        }
        recognizer = r
        val my = ++session
        var gotSpeech = false
        var finished = false
        fun done(block: () -> Unit) {
            if (finished || my != session) return
            finished = true; destroyNow(); block()
        }
        main.postDelayed({
            if (my == session && !finished) {
                HgLog.w("stt: timeout after ${timeoutMs}ms ($engine)")
                done { cb.onError("No speech heard (timeout)") }
            }
        }, timeoutMs)

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { HgLog.d("stt[$engine]: onReadyForSpeech"); cb.onListening(engine) }
            override fun onBeginningOfSpeech() { gotSpeech = true; HgLog.d("stt[$engine]: onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { HgLog.d("stt[$engine]: onEndOfSpeech") }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                HgLog.d("stt[$engine]: onPartialResults '${t.orEmpty()}'")
                if (!t.isNullOrBlank() && my == session) cb.onPartial(t)
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                HgLog.i("stt[$engine]: onResults '${t.orEmpty()}'")
                done { if (t.isNullOrBlank()) cb.onError("Didn't catch that") else cb.onResult(t) }
            }
            override fun onError(error: Int) {
                val name = errorName(error)
                HgLog.w("stt[$engine]: onError $error ($name), gotSpeech=$gotSpeech")
                if (finished || my != session) return
                // Retry with the default recognizer if on-device failed for a non-user reason.
                if (onDevice && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    finished = true; HgLog.i("stt: falling back to default recognizer")
                    main.postDelayed({ start(cb, preferOnDevice = false) }, startDelayMs); return
                }
                done { cb.onError("Speech error: $name") }
            }
            override fun onEvent(eventType: Int, params: Bundle?) { HgLog.d("stt[$engine]: onEvent $eventType") }
        })
        HgLog.i("stt: startListening ($engine)")
        try {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            })
        } catch (e: Exception) {
            HgLog.e("stt: startListening failed ($engine)", e)
            if (onDevice) { finished = true; start(cb, preferOnDevice = false) } else done { cb.onError("Couldn't start listening") }
        }
    }

    companion object {
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
