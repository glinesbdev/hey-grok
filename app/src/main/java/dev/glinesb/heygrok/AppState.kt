package dev.glinesb.heygrok

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Simple process-wide state shared between the service and the UI. */
object AppState {
    data class State(
        val listening: Boolean = false,
        val status: String = "Idle",
        val partial: String? = null,
        val lastQuestion: String? = null,
        val lastResult: String? = null,
        /** Recognizer input level (dB, ~-2…10) for the pop-up mic. */
        val rms: Float = -2f,
        /** Set by AppRouter when it hands off: "Grok", "Grok Bot", "Android Dev (Grok Bot)", "Hey Grok". */
        val sendingTo: String? = null,
        /** True while a question is being captured (STT or Grok Voice); pop-up timers never fire meanwhile. */
        val capturing: Boolean = false,
        /** Id of the current question session; UI and callbacks ignore anything from another id. */
        val session: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(block: (State) -> State) = _state.update(block)

    private val nextSession = java.util.concurrent.atomic.AtomicLong(0)

    /** New question: fresh id, and every per-question field cleared so nothing is replayed. */
    fun beginSession(): Long {
        val id = nextSession.incrementAndGet()
        _state.update { it.copy(session = id, partial = null, lastQuestion = null, lastResult = null, sendingTo = null, rms = -2f, capturing = true) }
        return id
    }

    /** Update only if [id] is still the current session (drops stale callbacks). */
    fun updateIf(id: Long, block: (State) -> State) = _state.update { if (it.session == id) block(it) else it }

    /** End/cancel [id]: stop capturing and clear the live transcript (keeps lastQuestion for the main screen). */
    fun endSession(id: Long) = updateIf(id) { it.copy(capturing = false, partial = null, rms = -2f) }
}
