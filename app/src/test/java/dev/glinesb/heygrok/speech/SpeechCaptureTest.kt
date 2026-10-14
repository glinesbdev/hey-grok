package dev.glinesb.heygrok.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCaptureTest {
    @Test fun incomplete() {
        assertTrue(SpeechCapture.looksIncomplete("what's the weather and"))
        assertTrue(SpeechCapture.looksIncomplete("remind me to"))
        assertTrue(SpeechCapture.looksIncomplete("so, um"))
        assertTrue(SpeechCapture.looksIncomplete("first,"))
    }
    @Test fun complete() {
        assertFalse(SpeechCapture.looksIncomplete("what's the weather in Denver"))
        assertFalse(SpeechCapture.looksIncomplete("tell Android Dev the build worked."))
        assertFalse(SpeechCapture.looksIncomplete(""))
    }
}
