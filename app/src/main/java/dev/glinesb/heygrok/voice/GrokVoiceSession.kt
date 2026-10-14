package dev.glinesb.heygrok.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One voice turn with Grok Voice (xAI speech-to-speech, OpenAI Realtime-compatible):
 * streams 24 kHz PCM16 mic audio with `input_audio_buffer.append`, lets `server_vad` decide when
 * the user stopped, plays `response.output_audio.delta` through AudioTrack, and ends after
 * `response.done`. The API key goes only into the Authorization header and is never logged.
 */
class GrokVoiceSession(
    private val apiKey: String,
    private val voice: String,
    private val listener: Listener,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build(),
) {
    interface Listener {
        fun onStatus(text: String) {}
        fun onUserTranscript(text: String) {}
        fun onAssistantTranscript(text: String) {}
        /** Called exactly once, when the turn ended or failed. */
        fun onFinished(error: String?)
    }

    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private val micOn = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val transcript = StringBuilder()

    fun start() {
        val req = Request.Builder()
            .url("$URL?model=$MODEL")
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(sessionUpdate().toString())
                startPlayer()
                startMic(webSocket)
                listener.onStatus("Grok Voice: listening…")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(JSONObject(text))
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Never include request headers here (they contain the key).
                finish("Grok Voice connection failed: ${response?.code ?: t.javaClass.simpleName}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(null)
        })
        // Safety net so a silent or stuck session can't keep the mic open forever.
        Thread { Thread.sleep(MAX_TURN_MS); if (!finished.get()) { ws?.close(1000, "timeout"); finish("Timed out") } }
            .apply { isDaemon = true }.start()
    }

    fun cancel() { ws?.cancel(); finish("Cancelled") }

    private fun sessionUpdate() = JSONObject().put("type", "session.update").put("session", JSONObject()
        .put("voice", voice)
        .put("instructions", "You are Grok, a concise, friendly voice assistant on the user's phone. Keep answers short.")
        .put("turn_detection", JSONObject().put("type", "server_vad"))
        .put("audio", JSONObject()
            .put("input", JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", RATE)))
            .put("output", JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", RATE)))))

    private fun handle(e: JSONObject) {
        when (e.optString("type")) {
            "input_audio_buffer.speech_stopped" -> { stopMic(); listener.onStatus("Grok is thinking…") }
            "response.output_audio.delta", "response.audio.delta" -> {
                stopMic()
                val pcm = Base64.decode(e.optString("delta"), Base64.DEFAULT)
                track?.write(pcm, 0, pcm.size)
            }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                transcript.append(e.optString("delta")); listener.onAssistantTranscript(transcript.toString())
            }
            "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.updated" -> listener.onUserTranscript(e.optString("transcript"))
            "response.done" -> {
                // Let the AudioTrack drain before closing.
                Thread { Thread.sleep(DRAIN_MS); ws?.close(1000, "turn complete") }.start()
            }
            "error" -> finish("Grok Voice error: ${e.optJSONObject("error")?.optString("message") ?: "unknown"}")
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the service runs
    private fun startMic(socket: WebSocket) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK_BYTES * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); finish("Mic unavailable"); return }
        recorder = rec; micOn.set(true)
        Thread({
            val buf = ByteArray(CHUNK_BYTES)
            rec.startRecording()
            while (micOn.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) socket.send(JSONObject().put("type", "input_audio_buffer.append")
                    .put("audio", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)).toString())
            }
            rec.stop(); rec.release()
        }, "grok-voice-mic").start()
    }

    private fun stopMic() { micOn.set(false) }

    private fun startPlayer() {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, RATE * 2))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
            .also { it.play() }
    }

    private fun finish(error: String?) {
        if (!finished.compareAndSet(false, true)) return
        stopMic()
        track?.runCatching { stop(); release() }; track = null
        if (error != null) Log.w(TAG, error)
        listener.onFinished(error)
    }

    companion object {
        private const val TAG = "GrokVoice"
        const val URL = "wss://api.x.ai/v1/realtime"
        const val MODEL = "grok-voice-latest"
        private const val RATE = 24_000
        private const val CHUNK_BYTES = RATE * 2 / 10 // 100 ms
        private const val DRAIN_MS = 1_500L
        private const val MAX_TURN_MS = 90_000L
    }
}
