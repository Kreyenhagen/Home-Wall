package com.kreyenhagen.homewall.data.layout

import com.kreyenhagen.homewall.util.writeTextAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface LayoutState {
    data object Loading : LayoutState
    data class Ready(val layout: WallLayout) : LayoutState

    /** The saved file has problems. Nothing writes over it until it's fixed or replaced. */
    data class Invalid(val problems: List<String>) : LayoutState
}

val LayoutState.layoutOrNull: WallLayout? get() = (this as? LayoutState.Ready)?.layout

/**
 * The single owner of `wall_layout.json` (§4, §6.1).
 *
 * On first launch it copies the bundled asset if there is one, otherwise it uses the placeholder
 * (§6.4). The placeholder is only written to disk once something changes it, so an app update
 * that adds a real `assets/wall_layout.json` still gets picked up.
 */
class LayoutRepository(
    private val file: File,
    private val readAsset: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<LayoutState>(LayoutState.Loading)
    val state: StateFlow<LayoutState> = _state.asStateFlow()

    private val mutex = Mutex()

    suspend fun load() = mutex.withLock {
        _state.value = withContext(io) {
            try {
                loadFromDisk()
            } catch (e: IOException) {
                LayoutState.Invalid(listOf("Couldn't read wall_layout.json: ${e.message}"))
            }
        }
    }

    private fun loadFromDisk(): LayoutState {
        if (file.exists()) return read(file.readText(), "wall_layout.json")
        val asset = readAsset() ?: return LayoutState.Ready(PlaceholderLayout.create())
        return read(asset, "assets/wall_layout.json").also {
            if (it is LayoutState.Ready) write(it.layout)
        }
    }

    sealed interface UpdateResult {
        data object Saved : UpdateResult
        data class Rejected(val problems: List<String>) : UpdateResult
    }

    /** Applies [change] to the current layout, validates the result and saves it atomically. */
    suspend fun update(change: (WallLayout) -> WallLayout): UpdateResult = mutex.withLock {
        val current = _state.value.layoutOrNull
            ?: return UpdateResult.Rejected(listOf("The wall layout isn't loaded, so it can't be changed."))
        val updated = change(current)
        val problems = LayoutValidator.validate(updated)
        if (problems.isNotEmpty()) return UpdateResult.Rejected(problems)
        try {
            withContext(io) { write(updated) }
        } catch (e: IOException) {
            return UpdateResult.Rejected(listOf("Couldn't save the layout: ${e.message}"))
        }
        _state.value = LayoutState.Ready(updated)
        UpdateResult.Saved
    }

    /**
     * Replaces the whole layout, e.g. from an import I confirmed (§12.3). Unlike [update], this also
     * works when the saved file is broken: replacing it is how it gets fixed.
     */
    suspend fun replace(layout: WallLayout): UpdateResult = mutex.withLock {
        val problems = LayoutValidator.validate(layout)
        if (problems.isNotEmpty()) return UpdateResult.Rejected(problems)
        try {
            withContext(io) { write(layout) }
        } catch (e: IOException) {
            return UpdateResult.Rejected(listOf("Couldn't save the layout: ${e.message}"))
        }
        _state.value = LayoutState.Ready(layout)
        UpdateResult.Saved
    }

    private fun read(text: String, source: String): LayoutState = when (val result = LayoutJson.parse(text)) {
        is LayoutJson.ParseResult.Ok -> LayoutState.Ready(result.layout)
        is LayoutJson.ParseResult.Failed -> LayoutState.Invalid(result.problems.map { "$source: $it" })
    }

    private fun write(layout: WallLayout) = writeTextAtomically(file, LayoutJson.encode(layout))
}
