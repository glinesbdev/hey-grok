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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(block: (State) -> State) = _state.update(block)
}
