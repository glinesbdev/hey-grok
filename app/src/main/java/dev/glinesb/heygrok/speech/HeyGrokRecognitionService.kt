package dev.glinesb.heygrok.speech

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.glinesb.heygrok.HgLog

/**
 * A VoiceInteractionService must declare a recognition service, and the system may make it the
 * device's default speech recognizer while we hold the assistant role. So that other apps' voice
 * input keeps working, this service is a transparent proxy: it forwards every request to Google's
 * recognizer (on-device when available) and relays all callbacks unchanged.
 */
class HeyGrokRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        delegate?.destroy()
        val ext = Recognizers.external(this)
        val r = when {
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            ext != null -> SpeechRecognizer.createSpeechRecognizer(this, ext)
            else -> null
        }
        HgLog.i("recog-proxy: start → ${if (r == null) "none" else ext ?: "on-device"}")
        if (r == null) { runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        delegate = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { runCatching { listener.readyForSpeech(p ?: Bundle()) } }
            override fun onBeginningOfSpeech() { runCatching { listener.beginningOfSpeech() } }
            override fun onRmsChanged(v: Float) { runCatching { listener.rmsChanged(v) } }
            override fun onBufferReceived(b: ByteArray?) { runCatching { listener.bufferReceived(b ?: ByteArray(0)) } }
            override fun onEndOfSpeech() { runCatching { listener.endOfSpeech() } }
            override fun onError(e: Int) { HgLog.d("recog-proxy: error $e"); runCatching { listener.error(e) } }
            override fun onResults(b: Bundle?) { runCatching { listener.results(b ?: Bundle()) } }
            override fun onPartialResults(b: Bundle?) { runCatching { listener.partialResults(b ?: Bundle()) } }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) { delegate?.stopListening() }
    override fun onCancel(listener: Callback) { delegate?.cancel() }
    override fun onDestroy() { delegate?.destroy(); delegate = null; super.onDestroy() }
}
