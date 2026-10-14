package dev.glinesb.heygrok.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One-shot question capture with Android's SpeechRecognizer. Must run on the main
 * thread; we post there. Prefers the on-device recognizer when available (API 31+).
 */
class SpeechCapture(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    fun capture(onResult: (String) -> Unit, onError: (String) -> Unit) = main.post {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("No speech recognizer on device"); return@post
        }
        cancel()
        val r = if (android.os.Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                cancel()
                if (text.isNullOrBlank()) onError("Didn't catch that") else onResult(text)
            }
            override fun onError(error: Int) { cancel(); onError("code $error") }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    fun cancel() {
        main.post { recognizer?.destroy(); recognizer = null }
    }
}
