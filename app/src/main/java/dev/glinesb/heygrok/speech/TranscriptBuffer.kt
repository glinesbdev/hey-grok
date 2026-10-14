package dev.glinesb.heygrok.speech

/**
 * Accumulates one utterance across recognizer segments (auto-restart + append).
 * One instance per capture; [reset] at every new session so nothing from a previous question leaks.
 */
class TranscriptBuffer {
    private val segments = mutableListOf<String>()
    var partial: String = ""; private set

    /** Live partial for the current segment; returns true if it changed (counts as speech). */
    fun setPartial(t: String): Boolean {
        val n = t.trim()
        if (n.isEmpty() || n == partial) return false
        partial = n; return true
    }

    /** Segment ended: keep its final text (or the last partial if the final came back empty). */
    fun commitSegment(finalText: String?) {
        val t = finalText?.trim().orEmpty().ifEmpty { partial }
        if (t.isNotEmpty()) segments += t
        partial = ""
    }

    fun clearPartial() { partial = "" }
    fun committed(): String = segments.joinToString(" ")
    fun text(): String = (committed() + " " + partial).trim()
    val isEmpty: Boolean get() = segments.isEmpty() && partial.isEmpty()
    fun reset() { segments.clear(); partial = "" }
}
