package dev.glinesb.heygrok.assist

import dev.glinesb.heygrok.wake.WakePhrase

/** Where button/assistant launches (no wake phrase) send the question. */
enum class LaunchTarget(val label: String) {
    GROK("Grok"), GROK_BOT("Grok Bot"), ASK("Ask each time");

    /** Routing hint for [dev.glinesb.heygrok.router.AppRouter], or null when the user still has to choose. */
    val phrase: WakePhrase? get() = when (this) { GROK -> WakePhrase.HEY_GROK; GROK_BOT -> WakePhrase.HEY_GROK_BOT; ASK -> null }

    companion object {
        fun parse(s: String?): LaunchTarget? = entries.firstOrNull { it.name == s }

        /** Default when the user hasn't chosen: Grok Bot if it's the only app installed, otherwise Ask each time. */
        fun default(grokInstalled: Boolean, botInstalled: Boolean): LaunchTarget =
            if (botInstalled && !grokInstalled) GROK_BOT else ASK
    }
}

/**
 * Spoken target override: "grok bot, what's …" → Grok Bot, "grok what's …" → Grok, prefix stripped.
 * Tolerates a leading "hey", "grokbot"/"grok-bot", and punctuation the recognizer adds.
 */
object SpokenPrefix {
    data class Result(val phrase: WakePhrase?, val question: String)

    private val BOT = Regex("""^\s*(?:hey\s+)?grok[\s\-]*bot\b[\s,.:;!?\-]*""", RegexOption.IGNORE_CASE)
    private val GROK = Regex("""^\s*(?:hey\s+)?grok\b[\s,.:;!?\-]*""", RegexOption.IGNORE_CASE)

    fun parse(transcript: String): Result {
        BOT.find(transcript)?.let { return Result(WakePhrase.HEY_GROK_BOT, transcript.substring(it.range.last + 1).trim()) }
        GROK.find(transcript)?.let { return Result(WakePhrase.HEY_GROK, transcript.substring(it.range.last + 1).trim()) }
        return Result(null, transcript.trim())
    }
}
