package com.kreyenhagen.homewall.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory ring buffer of BLE and LED log lines, shown in Settings → Diagnostics (§8.5).
 * Lines are also mirrored to Logcat. Thread-safe.
 */
class LogBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Line(val timeMs: Long, val tag: String, val message: String)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val _lines = MutableStateFlow<List<Line>>(emptyList())

    /** Oldest first, at most [capacity] lines. */
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    fun log(tag: String, message: String) {
        val line = Line(clock(), tag, message)
        _lines.update { old ->
            if (old.size < capacity) old + line else old.drop(old.size - capacity + 1) + line
        }
        Log.d("HomeWall/$tag", message)
    }

    fun clear() {
        _lines.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
