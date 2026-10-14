package dev.glinesb.heygrok.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the real ONNX models (assets/oww) over small synthetic WAV clips (src/test/resources/clips),
 * streaming 80 ms frames exactly like the app does. Clip names encode the expected outcome:
 *   pos_hey_grok_*.wav → HEY_GROK, pos_hey_grok_bot_*.wav → HEY_GROK_BOT, neg_*.wav → nothing.
 */
class OwwPipelineTest {
    private val assets = File("src/main/assets/oww")
    private fun bytes(n: String) = File(assets, n).readBytes()

    private fun readWav16k(f: File): ShortArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.limit()) {               // walk RIFF chunks to "data"
            val id = String(ByteArray(4) { b.get(pos + it) }); val len = b.getInt(pos + 4)
            if (id == "data") return ShortArray(len / 2) { b.getShort(pos + 8 + it * 2) }
            pos += 8 + len
        }
        error("no data chunk in $f")
    }

    private fun detect(p: OwwPipeline, pcm: ShortArray): Pair<WakePhrase?, Map<String, Float>> {
        p.reset(); val d = WakeDecider(); val max = mutableMapOf<String, Float>(); var hit: WakePhrase? = null
        val padded = ShortArray(pcm.size + OwwPipeline.SAMPLE_RATE) .also { pcm.copyInto(it) } // 1 s tail for the decider
        for (i in 0 + OwwPipeline.FRAME..padded.size step OwwPipeline.FRAME) {
            val s = p.process(padded.copyOfRange(i - OwwPipeline.FRAME, i)) ?: continue
            s.forEach { (k, v) -> max[k] = maxOf(max[k] ?: 0f, v) }
            if (hit == null) hit = d.update(s)
        }
        return hit to max
    }

    @Test fun clipsMatchExpectations() {
        val p = OwwPipeline(bytes("melspectrogram.onnx"), bytes("embedding_model.onnx"),
            mapOf(WakeDecider.KW_GROK to bytes("hey_grok.onnx"), WakeDecider.KW_BOT to bytes("hey_grok_bot.onnx")))
        val clips = File(javaClass.getResource("/clips")!!.toURI()).listFiles()!!.filter { it.extension == "wav" }.sorted()
        assertTrue("need clips", clips.size >= 4)
        p.use {
            for (c in clips) {
                val (hit, max) = detect(p, readWav16k(c))
                println("${c.name}: hit=$hit max=$max")
                val expected = when {
                    c.name.startsWith("pos_hey_grok_bot") -> WakePhrase.HEY_GROK_BOT
                    c.name.startsWith("pos_hey_grok") -> WakePhrase.HEY_GROK
                    else -> null
                }
                assertEquals(c.name, expected, hit)
            }
        }
    }
}
