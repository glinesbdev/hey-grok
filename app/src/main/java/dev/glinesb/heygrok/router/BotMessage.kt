package dev.glinesb.heygrok.router

import java.net.URLDecoder

/** A named Grok Bot agent the user added in Settings. */
data class BotContact(val name: String, val agentId: String)

/**
 * "Hey Grok Bot, tell Android Dev the build worked" → (Android Dev, "the build worked").
 * Pure Kotlin so it's unit-testable; matching is loose (case, punctuation, spacing, small typos).
 */
object BotMessage {
    data class Parsed(val contact: BotContact, val message: String)

    private val VERB = Regex("""^\s*(?:please\s+)?(?:tell|ask|message|text|ping)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val GLUE = Regex("""^(?:that|to|:|,|-)\s+""", RegexOption.IGNORE_CASE)

    fun parse(transcript: String, contacts: List<BotContact>): Parsed? {
        if (contacts.isEmpty()) return null
        val rest = VERB.find(transcript)?.groupValues?.get(1) ?: return null
        val words = rest.split(Regex("""\s+""")).filter { it.isNotBlank() }
        var best: Pair<BotContact, Int>? = null; var bestScore = 0.0
        for (c in contacts) {
            val n = norm(c.name).split(' ').size
            // try name lengths around the contact's word count (recognizer may split/merge words)
            for (k in (n - 1).coerceAtLeast(1)..(n + 1).coerceAtMost(words.size - 1)) {
                val s = similarity(norm(words.take(k).joinToString(" ")).replace(" ", ""), norm(c.name).replace(" ", ""))
                if (s > bestScore) { bestScore = s; best = c to k }
            }
        }
        val (contact, k) = best ?: return null
        if (bestScore < 0.75) return null
        val msg = words.drop(k).joinToString(" ").replace(Regex("""^[,:\-]+\s*"""), "").replace(GLUE, "").trim()
        return if (msg.isEmpty()) null else Parsed(contact, msg)
    }

    /** Accepts a bare id, grokbot://app/v1/agent?id=…, or https://x.ai/bot/agent/…  */
    fun extractAgentId(input: String): String? {
        val s = input.trim()
        Regex("""[?&]id=([^&#\s]+)""").find(s)?.let { return URLDecoder.decode(it.groupValues[1], "UTF-8") }
        Regex("""/bot/agent/([^/?#\s]+)""").find(s)?.let { return it.groupValues[1] }
        return s.takeIf { it.matches(Regex("""[A-Za-z0-9_\-]{6,}""")) }
    }

    /** Link registered by ai.x.grok.bot (manifest: VIEW, scheme "grokbot"); same format as the desktop app. */
    fun agentLink(agentId: String) = "grokbot://app/v1/agent?id=$agentId"

    fun encode(list: List<BotContact>) = list.joinToString("\n") { "${it.name.replace('\t', ' ').replace('\n', ' ')}\t${it.agentId}" }
    fun decode(s: String?): List<BotContact> = s.orEmpty().lines().mapNotNull { l ->
        l.split('\t').takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { BotContact(it[0], it[1]) }
    }

    internal fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex("""\s+"""), " ").trim()

    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }
}
