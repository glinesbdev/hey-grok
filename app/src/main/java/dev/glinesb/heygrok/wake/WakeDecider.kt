package dev.glinesb.heygrok.wake

/**
 * Turns per-frame (80 ms) scores of the two keyword models into a single decision.
 *
 * - A model "fires" once its score stays >= its threshold for `patience` consecutive frames.
 *   Requiring a few frames removes most one-off false accepts on continuous speech.
 * - "hey grok" is a prefix of "hey grok bot", so after the grok model fires we wait up to
 *   [botWaitFrames] for the bot model before reporting plain HEY_GROK.
 * - After a detection, [refractoryFrames] are ignored so one utterance yields one event.
 *
 * Defaults are tuned on held-out synthetic data (training/decider_eval.py); see MODELS.md.
 * This class must stay in sync with `decide()` in training/decider_eval.py.
 */
class WakeDecider(
    private val grokThreshold: Float = DEFAULT_GROK_THRESHOLD,
    private val botThreshold: Float = DEFAULT_BOT_THRESHOLD,
    private val grokPatience: Int = DEFAULT_GROK_PATIENCE,
    private val botPatience: Int = DEFAULT_BOT_PATIENCE,
    private val botWaitFrames: Int = 8,
    private val refractoryFrames: Int = 12,
) {
    private var grokRun = 0
    private var botRun = 0
    private var pending = -1
    private var cooldown = 0

    fun reset() { grokRun = 0; botRun = 0; pending = -1; cooldown = 0 }

    fun update(scores: Map<String, Float>): WakePhrase? {
        if (cooldown > 0) { cooldown--; grokRun = 0; botRun = 0; return null }
        val hasBot = KW_BOT in scores
        grokRun = if ((scores[KW_GROK] ?: 0f) >= grokThreshold) grokRun + 1 else 0
        botRun = if ((scores[KW_BOT] ?: 0f) >= botThreshold) botRun + 1 else 0

        if (hasBot && botRun >= botPatience) return fire(WakePhrase.HEY_GROK_BOT)
        if (pending >= 0) {
            if (++pending >= botWaitFrames) return fire(WakePhrase.HEY_GROK)
        } else if (grokRun >= grokPatience) {
            if (!hasBot) return fire(WakePhrase.HEY_GROK)
            pending = 0
        }
        return null
    }

    private fun fire(p: WakePhrase): WakePhrase { pending = -1; cooldown = refractoryFrames; return p }

    companion object {
        const val KW_GROK = "hey_grok"
        const val KW_BOT = "hey_grok_bot"
        // Tuned for v4 models (MODELS.md): 91.7 % / 99.3 % in-context recall, 0 FA/h on plain speech,
        // 3 FA/h on a confusable-heavy stream. Raise patience for fewer false accepts, lower for faster response.
        const val DEFAULT_GROK_THRESHOLD = 0.7f
        const val DEFAULT_BOT_THRESHOLD = 0.9f
        const val DEFAULT_GROK_PATIENCE = 5
        const val DEFAULT_BOT_PATIENCE = 5
    }
}
