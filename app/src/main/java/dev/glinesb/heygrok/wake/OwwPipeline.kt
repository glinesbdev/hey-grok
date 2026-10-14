package dev.glinesb.heygrok.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Pure-Kotlin port of openWakeWord's streaming feature pipeline (no Android deps, unit-testable):
 *
 *   16 kHz int16 PCM, 1280-sample (80 ms) frames
 *     → melspectrogram.onnx   (last 1760 samples → 8 mel frames × 32, then x/10 + 2)
 *     → embedding_model.onnx  (last 76 mel frames → 96-d embedding)
 *     → keyword models        (last 16 embeddings [1,16,96] → score 0..1)
 *
 * Mirrors openwakeword/utils.py AudioFeatures._streaming_features.
 */
class OwwPipeline(
    melspec: ByteArray,
    embedding: ByteArray,
    keywords: Map<String, ByteArray>,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : AutoCloseable {

    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val melSession = env.createSession(melspec, opts)
    private val embSession = env.createSession(embedding, opts)
    private val kwSessions = keywords.mapValues { env.createSession(it.value, opts) }

    private val raw = FloatArray(RAW_CONTEXT)                  // last 1760 samples
    private val mel = ArrayDeque<FloatArray>()                 // mel frames (32 each)
    private val emb = ArrayDeque<FloatArray>()                 // embeddings (96 each)

    init { reset() }

    fun reset() {
        // Start empty: windows padded with placeholder values produce spurious scores, so we
        // only score once 76 real mel frames and 16 real embeddings exist (~2 s after start).
        raw.fill(0f); mel.clear(); emb.clear()
    }

    /** Feed exactly [FRAME] samples. Returns keyword→score, or null until 16 embeddings exist. */
    fun process(frame: ShortArray): Map<String, Float>? {
        require(frame.size == FRAME) { "frame must be $FRAME samples" }
        System.arraycopy(raw, FRAME, raw, 0, RAW_CONTEXT - FRAME)
        for (i in 0 until FRAME) raw[RAW_CONTEXT - FRAME + i] = frame[i].toFloat()

        run1(melSession, raw, longArrayOf(1, RAW_CONTEXT.toLong())).let { out ->
            val frames = out.size / MEL_BINS
            for (f in 0 until frames) {
                mel.addLast(FloatArray(MEL_BINS) { out[f * MEL_BINS + it] / 10f + 2f })
            }
        }
        while (mel.size > MEL_WINDOW) mel.removeFirst()
        if (mel.size < MEL_WINDOW) return null

        val melIn = FloatArray(MEL_WINDOW * MEL_BINS)
        mel.forEachIndexed { i, m -> System.arraycopy(m, 0, melIn, i * MEL_BINS, MEL_BINS) }
        emb.addLast(run1(embSession, melIn, longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)))
        while (emb.size > EMB_WINDOW) emb.removeFirst()
        if (emb.size < EMB_WINDOW) return null

        val kwIn = FloatArray(EMB_WINDOW * EMB_DIM)
        emb.forEachIndexed { i, e -> System.arraycopy(e, 0, kwIn, i * EMB_DIM, EMB_DIM) }
        return kwSessions.mapValues { (_, s) -> run1(s, kwIn, longArrayOf(1, EMB_WINDOW.toLong(), EMB_DIM.toLong()))[0] }
    }

    private fun run1(s: OrtSession, data: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val v = r[0].value
                flatten(v)
            }
        }

    private fun flatten(v: Any?): FloatArray = when (v) {
        is FloatArray -> v
        is Array<*> -> v.map { flatten(it) }.let { parts ->
            FloatArray(parts.sumOf { it.size }).also { out -> var o = 0; parts.forEach { p -> p.copyInto(out, o); o += p.size } }
        }
        else -> error("unexpected ONNX output ${v?.javaClass}")
    }

    override fun close() {
        (kwSessions.values + melSession + embSession).forEach { it.close() }
        opts.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = 1280
        const val RAW_CONTEXT = FRAME + 160 * 3
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val EMB_DIM = 96
        const val EMB_WINDOW = 16
    }
}
