package dev.glinesb.heygrok.wake

/**
 * Turns per-frame scores of the two keyword models into a single decision.
 * "hey grok" is a prefix of "hey grok bot", so after "hey grok" crosses its threshold we wait
 * up to [botWaitFrames] (80 ms each) for the bot model before reporting plain HEY_GROK.
 */
class WakeDecider(
    private val grokThreshold: Float = 0.5f,
    private val botThreshold: Float = 0.5f,
    private val botWaitFrames: Int = 8,
) {
    private var pending = -1

    fun reset() { pending = -1 }

    fun update(scores: Map<String, Float>): WakePhrase? {
        val bot = scores[KW_BOT] ?: 0f
        val grok = scores[KW_GROK] ?: 0f
        if (bot >= botThreshold) { reset(); return WakePhrase.HEY_GROK_BOT }
        if (pending >= 0) {
            if (++pending >= botWaitFrames) { reset(); return WakePhrase.HEY_GROK }
        } else if (grok >= grokThreshold) {
            if (KW_BOT !in scores) return WakePhrase.HEY_GROK
            pending = 0
        }
        return null
    }

    companion object {
        const val KW_GROK = "hey_grok"
        const val KW_BOT = "hey_grok_bot"
    }
}
