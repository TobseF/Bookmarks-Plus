package de.bookmarksplus.enrich

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Pause switch for background runs: the work loops call [await] before every entry and
 * wait there as long as paused. Entries already started run to completion.
 */
class PauseGate {
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    fun pause() { _paused.value = true }
    fun resume() { _paused.value = false }

    /** Waits (cancellable) until the run is resumed. */
    suspend fun await() {
        if (_paused.value) _paused.first { !it }
    }
}
