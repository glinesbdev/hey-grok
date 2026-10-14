package dev.glinesb.heygrok.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.glinesb.heygrok.HgLog

/**
 * openWakeWord on ONNX Runtime Mobile. Reads 16 kHz mono PCM with AudioRecord on a background
 * thread and runs [OwwPipeline] on 80 ms frames. Models live in assets/oww/ (see MODELS.md).
 *
 * Thresholds are configurable: raise them to cut false accepts, lower them to improve recall.
 */
class OpenWakeWordEngine(
    private val context: Context,
    private val grokThreshold: Float = WakeDecider.DEFAULT_GROK_THRESHOLD,
    private val botThreshold: Float = WakeDecider.DEFAULT_BOT_THRESHOLD,
) : WakeWordEngine {

    private var pipeline: OwwPipeline? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    private fun asset(name: String) = context.assets.open("oww/$name").use { it.readBytes() }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before the service starts
    override fun start(onWake: (WakePhrase) -> Unit, onError: (Throwable) -> Unit) {
        if (running) return
        try {
            if (pipeline == null) pipeline = OwwPipeline(
                asset("melspectrogram.onnx"), asset("embedding_model.onnx"),
                mapOf(WakeDecider.KW_GROK to asset("hey_grok.onnx"), WakeDecider.KW_BOT to asset("hey_grok_bot.onnx")),
            )
        } catch (e: Exception) { onError(e); return }

        val minBuf = AudioRecord.getMinBufferSize(OwwPipeline.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, OwwPipeline.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, OwwPipeline.FRAME * 2 * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); onError(IllegalStateException("AudioRecord init failed")); return }

        running = true
        val p = pipeline!!.also { it.reset() }
        val decider = WakeDecider(grokThreshold, botThreshold)
        thread = Thread({
            val frame = ShortArray(OwwPipeline.FRAME)
            var detected: WakePhrase? = null
            var frames = 0L
            try {
                rec.startRecording()
                HgLog.i("wake: listening (AudioRecord started, ${OwwPipeline.SAMPLE_RATE} Hz)")
                while (running) {
                    var n = 0
                    while (n < frame.size && running) {
                        val r = rec.read(frame, n, frame.size - n)
                        if (r < 0) throw IllegalStateException("AudioRecord.read error $r")
                        n += r
                    }
                    if (!running) break
                    val scores = p.process(frame) ?: continue
                    frames++
                    val top = scores.maxByOrNull { it.value }
                    if (top != null && top.value >= 0.3f) HgLog.d("wake: score ${top.key}=${"%.3f".format(top.value)}")
                    else if (frames % 125 == 0L) HgLog.d("wake: alive, frames=$frames")  // every ~10 s
                    decider.update(scores)?.let { phrase -> detected = phrase; running = false }
                }
            } catch (e: Exception) {
                HgLog.e("wake: loop error", e); running = false; onError(e)
            } finally {
                // Release the mic BEFORE notifying, so SpeechRecognizer / Grok Voice can open it.
                runCatching { rec.stop() }; rec.release()
                HgLog.i("wake: AudioRecord released")
            }
            detected?.let { HgLog.i("wake: DETECTED $it"); onWake(it) }
        }, "oww-wake").apply { start() }
    }

    override fun stop() {
        running = false
        thread?.takeIf { it != Thread.currentThread() }?.join(500)
        thread = null
    }

    override fun release() {
        stop()
        pipeline?.close(); pipeline = null
    }

    companion object {
        private const val TAG = "OpenWakeWordEngine"
    }
}
