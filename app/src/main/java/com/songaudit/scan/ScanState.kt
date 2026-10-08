package com.songaudit.scan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase(val label: String) {
    IDLE("Idle"),
    FINDING("Finding files"),
    READING("Reading tags"),
    HASHING("Comparing copies"),
    MATCHING("Matching duplicates"),
    LISTENING("Listening"),
    FIXING("Fixing tags and covers"),
    SHRINKING("Shrinking to true size"),
}

/** Where a scan is. One per process; the service writes it, the screens read it. */
data class Progress(
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val found: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val current: String = "",
    val waitingForCharger: Boolean = false,
    val startedListening: Long = 0,
    val error: String? = null,
    /** Bumped whenever the database has something new for the screens to load. */
    val version: Int = 0,
) {
    /** Seconds left in the listen, from bytes per second so far; null until there is enough to say. */
    val etaSeconds: Long?
        get() {
            if (phase != Phase.LISTENING || bytesDone <= 0 || startedListening == 0L) return null
            val elapsed = (System.currentTimeMillis() - startedListening) / 1000.0
            if (elapsed < 20) return null
            return ((bytesTotal - bytesDone) / (bytesDone / elapsed)).toLong()
        }
}

object ScanState {
    private val state = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = state

    fun start() = state.update { Progress(running = true, phase = Phase.FINDING, version = it.version + 1) }

    fun finish(error: String?) = state.update {
        it.copy(running = false, phase = Phase.IDLE, current = "", waitingForCharger = false, error = error, version = it.version + 1)
    }

    fun phase(phase: Phase, total: Int = 0, bytes: Long = 0) = state.update {
        it.copy(
            phase = phase, done = 0, total = total, bytesDone = 0, bytesTotal = bytes, current = "",
            startedListening = if (phase == Phase.LISTENING) System.currentTimeMillis() else 0,
            // New rows are visible as soon as a phase that writes them ends.
            version = it.version + 1,
        )
    }

    fun found(n: Int) = state.update { it.copy(found = n) }
    fun read(done: Int, name: String) = state.update { it.copy(done = done, current = name) }
    fun listening(name: String) = state.update { it.copy(current = name) }
    fun waiting(waiting: Boolean) {
        if (state.value.waitingForCharger != waiting) state.update { it.copy(waitingForCharger = waiting) }
    }

    /** Every 25 files the screens reload, so findings fill in while the listen runs. */
    fun listened(done: Int, bytes: Long) = state.update {
        it.copy(done = done, bytesDone = bytes, version = if (done % 25 == 0) it.version + 1 else it.version)
    }

    fun changed() = state.update { it.copy(version = it.version + 1) }

    /** A batch of fixes, which the service runs in place of a scan. */
    fun begin(phase: Phase, total: Int) = state.update { Progress(running = true, phase = phase, total = total, version = it.version + 1) }

    private val said = MutableStateFlow<String?>(null)

    /** What the last batch of fixes came to, for the screens to show once. */
    val notice: StateFlow<String?> = said

    fun say(text: String) {
        said.value = text
    }

    fun heard() {
        said.value = null
    }
}
