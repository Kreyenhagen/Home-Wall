package com.kreyenhagen.homewall.ui.wallsetup

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.LedAssignment
import com.kreyenhagen.homewall.data.layout.MappingSummary
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.layout.assignmentOf
import com.kreyenhagen.homewall.data.layout.firstUnmappedLed
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.layout.startedMapping
import com.kreyenhagen.homewall.data.layout.withLedAddedToHold
import com.kreyenhagen.homewall.data.layout.withLedCount
import com.kreyenhagen.homewall.data.layout.withLedHidden
import com.kreyenhagen.homewall.data.layout.withLedInNewHold
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.led.LedEntry
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.ui.components.UserMessage
import com.kreyenhagen.homewall.ui.wall.LedPreview
import com.kreyenhagen.homewall.ui.wall.WallPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An action that can be undone from its snackbar: the layout before and after it. */
data class MappingUndo(val before: WallLayout, val after: WallLayout, val led: Int)

data class MapLedsUiState(
    val loaded: Boolean = false,
    val layout: WallLayout? = null,
    val layoutProblems: List<String> = emptyList(),
    val image: ImageBitmap? = null,
    /** The placeholder is active: confirm the LED count to start mapping (§12.1). */
    val needsLedCount: Boolean = false,
    val suggestedLedCount: Int = MapLedsViewModel.DEFAULT_LED_COUNT,
    /** Where [suggestedLedCount] came from, e.g. "from the controller". */
    val suggestedLedCountSource: String = "",
    val current: Int = 0,
    /** e.g. "Not mapped yet", "In hold 36 with LED 36", "Hidden". */
    val status: String = "",
    val summary: MappingSummary? = null,
    /** A white ring around the hold the current LED is in. */
    val rings: Map<Int, Color> = emptyMap(),
    val ringSizeIn: Float = AppSettings().ringSizeIn,
    val ledPreview: Map<Int, Color> = emptyMap(),
    /** Only the current LED, in the setup cursor slot; null until mapping has started. */
    val ledFrame: LedFrame? = null,
    val saving: Boolean = false,
    val message: UserMessage? = null,
    val undo: MappingUndo? = null,
) {
    val ledCount: Int get() = layout?.ledCount ?: 0
    val canGoBack: Boolean get() = current > 0
    val canGoForward: Boolean get() = current < ledCount - 1
}

/**
 * Wall Setup → Map LEDs (§12.1): light one LED at a time and tap where it shows up. Every action
 * is saved straight away, and the step resumes at the first unmapped LED.
 */
class MapLedsViewModel(
    private val layoutRepo: LayoutRepository,
    photo: Flow<WallPhotoState>,
    settings: Flow<AppSettings>,
    connection: Flow<ConnectionState>,
    sentFrame: Flow<LedFrame?> = flowOf(null),
) : ViewModel() {

    private data class Local(
        val current: Int? = null,
        val saving: Boolean = false,
        val message: UserMessage? = null,
        val undo: MappingUndo? = null,
    )

    private val local = MutableStateFlow(Local())

    private val extras = combine(photo, settings, connection, sentFrame) { p, s, c, f -> Extras(p, s, c, f) }

    private data class Extras(val photo: WallPhotoState, val settings: AppSettings, val connection: ConnectionState, val sent: LedFrame?)

    val uiState: StateFlow<MapLedsUiState> =
        combine(layoutRepo.state, extras, local) { layoutState, x, l ->
            if (layoutState is LayoutState.Loading) return@combine MapLedsUiState()
            val layout = layoutState.layoutOrNull
            val (suggested, source) = suggestedCount(layout, x.connection)
            if (layout == null) {
                return@combine MapLedsUiState(loaded = true, layoutProblems = (layoutState as? LayoutState.Invalid)?.problems.orEmpty())
            }
            val current = (l.current ?: layout.firstUnmappedLed() ?: 0).coerceIn(0, layout.ledCount - 1)
            val mapping = !layout.placeholder
            val assignment = layout.assignmentOf(current)
            MapLedsUiState(
                loaded = true,
                layout = layout,
                image = x.photo.rectified,
                needsLedCount = layout.placeholder,
                suggestedLedCount = suggested,
                suggestedLedCountSource = source,
                current = current,
                status = statusOf(assignment, current),
                summary = MappingSummary.of(layout),
                rings = (assignment as? LedAssignment.InHold)?.let { mapOf(it.hold.id to Color.White) }.orEmpty(),
                ringSizeIn = x.settings.ringSizeIn,
                ledPreview = if (mapping && x.settings.showLedPreview) LedPreview.dots(x.sent, layout) else emptyMap(),
                ledFrame = if (mapping) LedFrame(listOf(LedEntry(current, Palette.SLOT_CURSOR))) else null,
                saving = l.saving,
                message = l.message,
                undo = l.undo,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapLedsUiState())

    // ---- Starting ----

    /** Confirms the LED count and replaces the placeholder with an empty layout (§12.1). */
    fun startMapping(count: Int) {
        if (count !in 1..MAX_LED_COUNT) return show("The LED count must be between 1 and $MAX_LED_COUNT.")
        val layout = layoutRepo.state.value.layoutOrNull ?: return
        save(undoLed = null) { layoutRepo.replace(layout.startedMapping(count)) }
        local.update { it.copy(current = 0) }
    }

    /** Changes the LED count later. Lowering it unmaps the LEDs past the new count. */
    fun changeLedCount(count: Int) {
        if (count !in 1..MAX_LED_COUNT) return show("The LED count must be between 1 and $MAX_LED_COUNT.")
        save(undoLed = null) { layoutRepo.update { it.withLedCount(count) } }
    }

    // ---- Moving between LEDs ----

    fun previous() = goTo(uiState.value.current - 1)

    fun next() = goTo(uiState.value.current + 1)

    /** The jump-to field. Returns false (and says why) if [text] isn't an LED index. */
    fun jumpTo(text: String): Boolean {
        val index = text.trim().toIntOrNull()
        val count = uiState.value.ledCount
        if (index == null || index !in 0 until count) {
            show("Enter an LED number from 0 to ${count - 1}.")
            return false
        }
        goTo(index)
        return true
    }

    /** From Review's "unmapped" (§12.2). */
    fun goToFirstUnmapped() {
        val layout = layoutRepo.state.value.layoutOrNull ?: return
        val first = layout.firstUnmappedLed()
        if (first == null) show("Every LED is mapped.") else goTo(first)
    }

    private fun goTo(index: Int) {
        val count = uiState.value.ledCount
        if (count == 0) return
        local.update { it.copy(current = index.coerceIn(0, count - 1)) }
    }

    // ---- Mapping actions (§12.1) ----

    /** Tap on an empty spot: a new hold there with this LED. */
    fun tapEmpty(point: WallPoint) {
        val state = uiState.value
        val layout = state.layout ?: return
        if (state.needsLedCount) return
        if (point.x !in 0f..layout.wall.widthIn || point.y !in 0f..layout.wall.heightIn) return show("Tap on the wall.")
        val led = state.current
        advanceFrom(led)
        save(undoLed = null, failedLed = led) { layoutRepo.update { it.withLedInNewHold(led, point.x, point.y) } }
    }

    /** Tap on an existing hold: pair this LED with it. */
    fun tapHold(holdId: Int) {
        val state = uiState.value
        val layout = state.layout ?: return
        if (state.needsLedCount) return
        val led = state.current
        if ((layout.assignmentOf(led) as? LedAssignment.InHold)?.hold?.id == holdId) {
            advanceFrom(led)
            return show("LED $led is already in hold $holdId.")
        }
        advanceFrom(led)
        save(undoLed = led, failedLed = led, message = "LED $led added to hold $holdId") {
            layoutRepo.update { it.withLedAddedToHold(led, holdId) }
        }
    }

    /** Hidden: the LED stays off for good. */
    fun hide() {
        val state = uiState.value
        if (state.layout == null || state.needsLedCount) return
        val led = state.current
        advanceFrom(led)
        save(undoLed = null, failedLed = led) { layoutRepo.update { it.withLedHidden(led) } }
    }

    /** Undo from the pairing snackbar: only if nothing else has changed the layout since. */
    fun undo() {
        val undo = local.value.undo ?: return
        local.update { it.copy(undo = null) }
        if (layoutRepo.state.value.layoutOrNull != undo.after) return show("Can't undo: the layout has changed since.")
        save(undoLed = null) { layoutRepo.replace(undo.before) }
        local.update { it.copy(current = undo.led) }
    }

    fun messageShown(id: Long) = local.update {
        if (it.message?.id == id) it.copy(message = null, undo = null) else it
    }

    /** Moves on after an action; at the last LED it stays and says so. */
    private fun advanceFrom(led: Int) {
        val count = uiState.value.ledCount
        if (led >= count - 1) {
            show("That was the last LED (LED $led).")
        } else {
            local.update { if ((it.current ?: led) == led) it.copy(current = led + 1) else it }
        }
    }

    private fun save(
        undoLed: Int?,
        failedLed: Int? = null,
        message: String? = null,
        change: suspend () -> LayoutRepository.UpdateResult,
    ) {
        val before = layoutRepo.state.value.layoutOrNull
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = change()
            local.update { it.copy(saving = false) }
            when (result) {
                is LayoutRepository.UpdateResult.Rejected -> {
                    // Go back to the LED that didn't save, so it isn't skipped.
                    local.update { l -> l.copy(current = failedLed ?: l.current, message = UserMessage(result.problems.joinToString("\n")), undo = null) }
                }
                LayoutRepository.UpdateResult.Saved -> if (message != null) {
                    val after = layoutRepo.state.value.layoutOrNull
                    val undo = if (undoLed != null && before != null && after != null) MappingUndo(before, after, undoLed) else null
                    local.update { it.copy(message = UserMessage(message), undo = undo) }
                }
            }
        }
    }

    private fun show(text: String) = local.update { it.copy(message = UserMessage(text), undo = null) }

    init {
        // Resume at the first unmapped LED (§12.1) once the layout is known.
        viewModelScope.launch {
            val layout = layoutRepo.state.first { it !is LayoutState.Loading }.layoutOrNull ?: return@launch
            local.update { if (it.current == null) it.copy(current = layout.firstUnmappedLed() ?: 0) else it }
        }
    }

    companion object {
        /** §2: the wall has 550 LEDs. */
        const val DEFAULT_LED_COUNT = 550
        const val MAX_LED_COUNT = 65_536

        fun suggestedCount(layout: WallLayout?, connection: ConnectionState): Pair<Int, String> = when {
            connection is ConnectionState.Connected -> connection.ledCount to "from the controller"
            layout != null && !layout.placeholder -> layout.ledCount to "from the layout"
            else -> DEFAULT_LED_COUNT to "the default"
        }

        fun statusOf(assignment: LedAssignment, led: Int): String = when (assignment) {
            LedAssignment.Unmapped -> "Not mapped yet"
            LedAssignment.Hidden -> "Hidden"
            is LedAssignment.InHold -> {
                val others = assignment.hold.leds - led
                "In hold ${assignment.hold.id}" + when (others.size) {
                    0 -> ""
                    1 -> " with LED ${others.first()}"
                    else -> " with LEDs ${others.joinToString(", ")}"
                }
            }
        }
    }
}
