package dev.glinesb.heygrok.wake

/** Which phrase woke us up. Drives routing preference. */
enum class WakePhrase { HEY_GROK, HEY_GROK_BOT }

/**
 * Abstraction over an on-device wake-word engine (openWakeWord today) so it can be swapped
 * for another engine later.
 */
interface WakeWordEngine {
    /** Begin listening on the microphone. [onWake] is invoked on detection. */
    fun start(onWake: (WakePhrase) -> Unit, onError: (Throwable) -> Unit)
    /** Stop listening and release the microphone (needed before SpeechRecognizer runs). */
    fun stop()
    /** Free native resources. */
    fun release()
}
