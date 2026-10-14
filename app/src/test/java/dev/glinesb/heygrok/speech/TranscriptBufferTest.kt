package dev.glinesb.heygrok.speech

import dev.glinesb.heygrok.AppState
import org.junit.Assert.*
import org.junit.Test

class TranscriptBufferTest {
    @Test fun accumulatesAcrossSegments() {
        val b = TranscriptBuffer()
        assertTrue(b.setPartial("what's the"))
        assertFalse("same partial is not new speech", b.setPartial("what's the"))
        b.commitSegment("what's the weather and")
        assertTrue(b.setPartial("will it rain"))
        assertEquals("what's the weather and will it rain", b.text())
        b.commitSegment("") // empty final → keeps the last partial
        assertEquals("what's the weather and will it rain", b.text())
    }

    @Test fun resetStartsEmpty() {
        val b = TranscriptBuffer()
        b.commitSegment("tell Android Dev the build worked"); b.setPartial("and")
        b.reset()
        assertTrue(b.isEmpty)
        assertEquals("", b.text())
        b.setPartial("new question")
        assertEquals("new question", b.text())
    }

    @Test fun sessionResetDropsStaleWrites() {
        val s1 = AppState.beginSession()
        AppState.updateIf(s1) { it.copy(partial = "old message", lastQuestion = "old message", sendingTo = "Grok") }
        val s2 = AppState.beginSession()
        assertNotEquals(s1, s2)
        AppState.state.value.let {
            assertEquals(s2, it.session)
            assertNull("new session starts empty", it.partial)
            assertNull(it.lastQuestion); assertNull(it.sendingTo)
            assertTrue(it.capturing)
        }
        AppState.updateIf(s1) { it.copy(partial = "late stale partial") } // stale callback
        assertNull(AppState.state.value.partial)
        AppState.endSession(s1) // stale end must not stop the new session
        assertTrue(AppState.state.value.capturing)
        AppState.updateIf(s2) { it.copy(partial = "new") }
        assertEquals("new", AppState.state.value.partial)
        AppState.endSession(s2)
        assertFalse(AppState.state.value.capturing); assertNull(AppState.state.value.partial)
    }
}
