package dev.glinesb.heygrok.wake

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import android.util.Log
import dev.glinesb.heygrok.BuildConfig

/**
 * Porcupine implementation.
 *
 * - AccessKey comes from `PICOVOICE_ACCESS_KEY` in local.properties (→ BuildConfig).
 * - Custom keywords are `.ppn` files in `app/src/main/assets/` (see assets/README.md).
 *   If they are missing we fall back to the built-in "computer" keyword for testing.
 */
class PorcupineWakeWordEngine(private val context: Context) : WakeWordEngine {

    private var manager: PorcupineManager? = null

    override fun start(onWake: (WakePhrase) -> Unit, onError: (Throwable) -> Unit) {
        val key = BuildConfig.PICOVOICE_ACCESS_KEY
        if (key.isBlank()) {
            onError(IllegalStateException("PICOVOICE_ACCESS_KEY missing in local.properties"))
            return
        }
        try {
            if (manager == null) manager = build(key, onWake)
            manager?.start()
        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine start failed", e)
            onError(e)
        }
    }

    private fun build(key: String, onWake: (WakePhrase) -> Unit): PorcupineManager {
        val assets = context.assets.list("")?.toSet().orEmpty()
        val custom = listOf(KEYWORD_HEY_GROK, KEYWORD_HEY_GROK_BOT).filter { it in assets }
        val builder = PorcupineManager.Builder().setAccessKey(key)
        val phrases: List<WakePhrase>
        if (custom.isNotEmpty()) {
            // Paths relative to assets are supported by Porcupine Android v2.1+.
            builder.setKeywordPaths(custom.toTypedArray())
            builder.setSensitivities(FloatArray(custom.size) { SENSITIVITY })
            phrases = custom.map { if (it == KEYWORD_HEY_GROK_BOT) WakePhrase.HEY_GROK_BOT else WakePhrase.HEY_GROK }
        } else {
            Log.w(TAG, "No .ppn files in assets; using built-in 'computer' keyword")
            builder.setKeyword(Porcupine.BuiltInKeyword.COMPUTER).setSensitivity(SENSITIVITY)
            phrases = listOf(WakePhrase.HEY_GROK)
        }
        return builder.build(context, PorcupineManagerCallback { index ->
            onWake(phrases.getOrElse(index) { WakePhrase.HEY_GROK })
        })
    }

    override fun stop() {
        try { manager?.stop() } catch (e: PorcupineException) { Log.w(TAG, "stop", e) }
    }

    override fun release() {
        stop()
        manager?.delete()
        manager = null
    }

    companion object {
        private const val TAG = "PorcupineEngine"
        const val KEYWORD_HEY_GROK = "hey-grok_android.ppn"
        const val KEYWORD_HEY_GROK_BOT = "hey-grok-bot_android.ppn"
        private const val SENSITIVITY = 0.6f
    }
}
