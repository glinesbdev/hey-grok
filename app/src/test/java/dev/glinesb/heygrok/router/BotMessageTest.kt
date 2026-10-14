package dev.glinesb.heygrok.router

import org.junit.Assert.*
import org.junit.Test

class BotMessageTest {
    private val dev = BotContact("Android Dev", "abc-123")
    private val ops = BotContact("Ops", "ops-999")
    private val all = listOf(dev, ops)

    @Test fun basic() = assertEquals(BotMessage.Parsed(dev, "the build worked"), BotMessage.parse("tell Android Dev the build worked", all))
    @Test fun loose() {
        assertEquals(BotMessage.Parsed(dev, "the build worked"), BotMessage.parse("Tell android-dev, the build worked", all))
        assertEquals(BotMessage.Parsed(dev, "ship it"), BotMessage.parse("message androiddev that ship it", all))
        assertEquals(BotMessage.Parsed(dev, "hi"), BotMessage.parse("ask Andriod Dev hi", all))
        assertEquals(BotMessage.Parsed(ops, "restart the server"), BotMessage.parse("ask ops to restart the server", all))
    }
    @Test fun noMatch() {
        assertNull(BotMessage.parse("what's the weather", all))
        assertNull(BotMessage.parse("tell me a joke", all))
        assertNull(BotMessage.parse("tell Android Dev", all))
        assertNull(BotMessage.parse("tell Android Dev hi", emptyList()))
    }
    @Test fun ids() {
        assertEquals("abc-123", BotMessage.extractAgentId("grokbot://app/v1/agent?id=abc-123"))
        assertEquals("abc-123", BotMessage.extractAgentId("https://x.ai/bot/agent/abc-123"))
        assertEquals("abc-123", BotMessage.extractAgentId(" abc-123 "))
        assertNull(BotMessage.extractAgentId("hello world"))
    }
    @Test fun roundTrip() = assertEquals(all, BotMessage.decode(BotMessage.encode(all)))
}
