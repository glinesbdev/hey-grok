package dev.glinesb.heygrok.assist

import dev.glinesb.heygrok.wake.WakePhrase
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchTargetTest {
    private fun p(s: String) = SpokenPrefix.parse(s)

    @Test fun botPrefix() {
        assertEquals(SpokenPrefix.Result(WakePhrase.HEY_GROK_BOT, "what's the weather"), p("Grok Bot, what's the weather"))
        assertEquals(SpokenPrefix.Result(WakePhrase.HEY_GROK_BOT, "remind me"), p("grok bot remind me"))
        assertEquals(SpokenPrefix.Result(WakePhrase.HEY_GROK_BOT, "hi"), p("grokbot hi"))
        assertEquals(SpokenPrefix.Result(WakePhrase.HEY_GROK_BOT, "hi"), p("Hey grok-bot: hi"))
    }
    @Test fun grokPrefix() {
        assertEquals(SpokenPrefix.Result(WakePhrase.HEY_GROK, "tell me a joke"), p("Grok, tell me a joke"))
        assertEquals(SpokenPrefix.Result(WakePhrase.HEY_GROK, "bottles of water"), p("grok bottles of water"))
    }
    @Test fun noPrefix() {
        assertEquals(SpokenPrefix.Result(null, "is grok good"), p("is grok good"))
        assertEquals(SpokenPrefix.Result(null, "grokking basics"), p("grokking basics"))
    }
    @Test fun defaults() {
        assertEquals(LaunchTarget.GROK_BOT, LaunchTarget.default(grokInstalled = false, botInstalled = true))
        assertEquals(LaunchTarget.ASK, LaunchTarget.default(grokInstalled = true, botInstalled = true))
        assertEquals(LaunchTarget.ASK, LaunchTarget.default(grokInstalled = true, botInstalled = false))
        assertEquals(LaunchTarget.ASK, LaunchTarget.default(grokInstalled = false, botInstalled = false))
    }
}
