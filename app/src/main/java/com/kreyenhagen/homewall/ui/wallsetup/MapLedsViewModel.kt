package com.kreyenhagen.homewall.ui.wallsetup

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.LedAssignment
import com.kreyenhagen.homewall.data.layout.MappingSummary
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.layout.assignmentOf
import com.kreyenhagen.homewall.data.layout.firstUnmappedLed
import com.kreyenhagen.homewall.data.layout.OffList
import com.kreyenhagen.homewall.data.layout.holdLedsProblem
import com.kreyenhagen.homewall.data.layout.knownHiddenLedsAvailable
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.layout.ledRanges
import com.kreyenhagen.homewall.data.layout.ledsIn
import com.kreyenhagen.homewall.data.layout.offListProblem
import com.kreyenhagen.homewall.data.layout.parseLedList
import com.kreyenhagen.homewall.data.layout.startedMapping
import com.kreyenhagen.homewall.data.layout.withHoldLeds
import com.kreyenhagen.homewall.data.layout.withHoldMoved
import com.kreyenhagen.homewall.data.layout.withLedAddedToHold
import com.kreyenhagen.homewall.data.layout.withLedCount
import com.kreyenhagen.homewall.data.layout.withLedHidden
import com.kreyenhagen.homewall.data.layout.withLedInNewHold
import com.kreyenhagen.homewall.data.layout.withLedUnused
import com.kreyenhagen.homewall.data.layout.withOffList
import com.kreyenhagen.homewall.data.layout.withoutHold
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

/** A tile that was pressed and held in Map LEDs (§12.1). While there is one, the wall lights its LEDs. */
sealed interface TileAction {
    val holdId: Int

    /** Being dragged to ([x], [y]); saved when the finger lifts. */
    data class Dragging(override val holdId: Int, val x: Float, val y: Float) : TileAction

    /** Held and let go: its menu is open. */
    data class Menu(override val holdId: Int) : TileAction

    /** The Change LEDs dialog is open. */
    data class EditLeds(override val holdId: Int) : TileAction
}

data class MapLedsUiState(
    val loaded: Boolean = false,
    /** The layout as shown: a tile being dragged is drawn where it is now. */
    val layout: WallLayout? = null,
    val layoutProblems: List<String> = emptyList(),
    val image: ImageBitmap? = null,
    /** The placeholder is active: confirm the LED count to start mapping (§12.1). */
    val needsLedCount: Boolean = false,
    val suggestedLedCount: Int = MapLedsViewModel.DEFAULT_LED_COUNT,
    /** Where [suggestedLedCount] came from, e.g. "from the controller". */
    val suggestedLedCountSource: String = "",
    val current: Int = 0,
    /** Where Prev and Next go: the nearest LEDs that aren't Hidden, or null at the ends. */
    val previousLed: Int? = null,
    val nextLed: Int? = null,
    /** e.g. "Not mapped yet", "Paired with LED 36", "Hidden". */
    val status: String = "",
    val summary: MappingSummary? = null,
    /** A white ring around the held tile, or else around the tile the current LED is in. */
    val rings: Map<Int, Color> = emptyMap(),
    val ringSizeIn: Float = AppSettings().ringSizeIn,
    val ledPreview: Map<Int, Color> = emptyMap(),
    /** The current LED, or the held tile's LEDs, in the setup cursor slot; null until mapping has started. */
    val ledFrame: LedFrame? = null,
    val tileAction: TileAction? = null,
    /** The tile [tileAction] is about. */
    val tile: Hold? = null,
    val saving: Boolean = false,
    val message: UserMessage? = null,
    val undo: MappingUndo? = null,
) {
    val ledCount: Int get() = layout?.ledCount ?: 0
    val canGoBack: Boolean get() = previousLed != null
    val canGoForward: Boolean get() = nextLed != null
    val menuTile: Hold? get() = tile.takeIf { tileAction is TileAction.Menu }
    val editingTile: Hold? get() = tile.takeIf { tileAction is TileAction.EditLeds }

    /** The Hidden or Unused list as the editor shows it, e.g. "0–40, 45, 54". */
    fun offListText(list: OffList): String = layout?.let { ledRanges(it.ledsIn(list)) }.orEmpty()
}

/**
 * Wall Setup → Map LEDs (§12.1): light one LED at a time and tap where it shows up. Every action
 * is saved straight away, and the step resumes at the first unmapped LED. Press and hold a tile to
 * drag it, or let go to change its LEDs or delete it.
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
        val tileAction: TileAction? = null,
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
            val saved = layoutState.layoutOrNull
            val (suggested, source) = suggestedCount(saved, x.connection)
            if (saved == null) {
                return@combine MapLedsUiState(loaded = true, layoutProblems = (layoutState as? LayoutState.Invalid)?.problems.orEmpty())
            }
            // A tile deleted elsewhere (e.g. by an undo) ends whatever was happening to it.
            val action = l.tileAction?.takeIf { a -> saved.holds.any { it.id == a.holdId } }
            val layout = (action as? TileAction.Dragging)?.let { saved.withHoldMoved(it.holdId, it.x, it.y) } ?: saved
            val tile = action?.let { a -> layout.holds.firstOrNull { it.id == a.holdId } }
            val current = (l.current ?: layout.firstUnmappedLed() ?: 0).coerceIn(0, layout.ledCount - 1)
            val mapping = !layout.placeholder
            val assignment = layout.assignmentOf(current)
            val ringed = tile ?: (assignment as? LedAssignment.InHold)?.hold
            val lit = tile?.leds ?: listOf(current)
            val hidden = layout.hiddenLeds.toHashSet()
            MapLedsUiState(
                loaded = true,
                layout = layout,
                image = x.photo.rectified,
                needsLedCount = layout.placeholder,
                suggestedLedCount = suggested,
                suggestedLedCountSource = source,
                current = current,
                previousLed = (current - 1 downTo 0).firstOrNull { it !in hidden },
                nextLed = (current + 1 until layout.ledCount).firstOrNull { it !in hidden },
                status = statusOf(assignment, current),
                summary = MappingSummary.of(layout),
                rings = ringed?.let { mapOf(it.id to Color.White) }.orEmpty(),
                ringSizeIn = x.settings.ringSizeIn,
                ledPreview = if (mapping && x.settings.showLedPreview) LedPreview.dots(x.sent, layout) else emptyMap(),
                ledFrame = if (mapping) LedFrame(lit.sorted().map { LedEntry(it, Palette.SLOT_CURSOR) }) else null,
                tileAction = action,
                tile = tile,
                saving = l.saving,
                message = l.message,
                undo = l.undo,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapLedsUiState())

    // ---- Starting ----

    /** Confirms the LED count and replaces the placeholder with an empty layout (§12.1). */
    fun startMapping(count: Int) {
        if (count !in 1..MAX_LED_COUNT) return show("The LED count must be between 1 and $MAX_LED_COUNT.")
        val started = layoutRepo.state.value.layoutOrNull?.startedMapping(count) ?: return
        save(undoLed = null) { layoutRepo.replace(started) }
        // The known hidden LEDs start Hidden, so begin at the first LED that's left to map.
        local.update { it.copy(current = started.firstUnmappedLed() ?: 0) }
    }

    /** Changes the LED count later. Lowering it unmaps the LEDs past the new count. */
    fun changeLedCount(count: Int) {
        if (count !in 1..MAX_LED_COUNT) return show("The LED count must be between 1 and $MAX_LED_COUNT.")
        save(undoLed = null) { layoutRepo.update { it.withLedCount(count) } }
    }

    // ---- Moving between LEDs ----

    /** Prev and Next skip Hidden LEDs, which are locked (§12.1). */
    fun previous() {
        uiState.value.previousLed?.let(::goTo)
    }

    fun next() {
        uiState.value.nextLed?.let(::goTo)
    }

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

    /** Tap on an empty spot: a new tile there with this LED. */
    fun tapEmpty(point: WallPoint) {
        val state = uiState.value
        val layout = state.layout ?: return
        if (state.needsLedCount || state.tileAction != null) return
        if (point.x !in 0f..layout.wall.widthIn || point.y !in 0f..layout.wall.heightIn) return show("Tap on the wall.")
        val led = state.current
        if (refuseIfHidden(layout, led)) return
        advanceFrom(led)
        save(undoLed = null, failedLed = led) { layoutRepo.update { it.withLedInNewHold(led, point.x, point.y) } }
    }

    /** Tap on an existing tile: pair this LED with it, so they light together. */
    fun tapHold(holdId: Int) {
        val state = uiState.value
        val layout = state.layout ?: return
        if (state.needsLedCount || state.tileAction != null) return
        val hold = layout.holds.firstOrNull { it.id == holdId } ?: return
        val led = state.current
        if (refuseIfHidden(layout, led)) return
        advanceFrom(led)
        if (led in hold.leds) return show("LED $led is already in that tile.")
        save(undoLed = led, failedLed = led, message = "LED $led paired with ${ledsText(hold.leds)}") {
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

    /** Unused: the LED is on the wall but has no hold in front of it, so it stays off and can't be picked. */
    fun markUnused() {
        val state = uiState.value
        val layout = state.layout ?: return
        if (state.needsLedCount) return
        val led = state.current
        if (refuseIfHidden(layout, led)) return
        advanceFrom(led)
        save(undoLed = null, failedLed = led) { layoutRepo.update { it.withLedUnused(led) } }
    }

    /**
     * Hidden LEDs are locked (§12.1): they can't be placed, paired or marked Unused until they're
     * taken out of the Hidden list. Says so and returns true if [led] is Hidden.
     */
    private fun refuseIfHidden(layout: WallLayout, led: Int): Boolean {
        if (led !in layout.hiddenLeds) return false
        show(hiddenLockedText(led))
        return true
    }

    // ---- Editing the Hidden and Unused lists (§12.1) ----

    /** What's wrong with [text] as the new [list], shown under the field; null if it can be saved. */
    fun offListProblem(list: OffList, text: String): String? {
        val layout = layoutRepo.state.value.layoutOrNull ?: return null
        return offListProblem(layout, list, text)
    }

    /**
     * Replaces [list] with the LEDs in [text]. LEDs taken out of it become unmapped. Returns false
     * (and says why) if it can't be saved, so the editor stays open.
     */
    fun saveOffList(list: OffList, text: String): Boolean {
        val layout = layoutRepo.state.value.layoutOrNull ?: return false
        if (layout.placeholder) return false
        offListProblem(layout, list, text)?.let {
            show(it)
            return false
        }
        val leds = parseLedList(text) ?: return false
        if (leds == layout.ledsIn(list)) return true
        val current = uiState.value.current
        save(undoLed = current, message = "${list.label} list saved: ${countText(leds.size)}") {
            layoutRepo.update { it.withOffList(list, leds) }
        }
        if (list == OffList.HIDDEN && current in leds) {
            // Hidden LEDs are locked, so don't stay on one: move to the nearest LED that isn't.
            val hidden = leds.toHashSet()
            val nearest = (current + 1 until layout.ledCount).firstOrNull { it !in hidden }
                ?: (current - 1 downTo 0).firstOrNull { it !in hidden }
            if (nearest != null) local.update { it.copy(current = nearest) }
        }
        return true
    }

    /**
     * [text] with the known hidden LEDs (§12.1) that the Hidden list can take added to it, or null
     * if that adds nothing (or [text] can't be read).
     */
    fun withKnownHidden(text: String): String? {
        val layout = layoutRepo.state.value.layoutOrNull ?: return null
        val leds = parseLedList(text) ?: return null
        val merged = (leds + layout.knownHiddenLedsAvailable()).distinct().sorted()
        return if (merged.size == leds.size) null else ledRanges(merged)
    }

    // ---- Press and hold a tile (§12.1) ----

    /** A tile was pressed and held: the wall lights its LEDs, and dragging may follow. */
    fun pressTile(holdId: Int) {
        val hold = savedHold(holdId) ?: return
        local.update { it.copy(tileAction = TileAction.Dragging(hold.id, hold.x, hold.y)) }
    }

    fun dragTile(dxIn: Float, dyIn: Float) {
        val wall = layoutRepo.state.value.layoutOrNull?.wall ?: return
        local.update { l ->
            val drag = l.tileAction as? TileAction.Dragging ?: return@update l
            l.copy(tileAction = drag.copy(x = (drag.x + dxIn).coerceIn(0f, wall.widthIn), y = (drag.y + dyIn).coerceIn(0f, wall.heightIn)))
        }
    }

    /** The finger lifted after dragging: save the tile's new spot. */
    fun endTileDrag() {
        val drag = local.value.tileAction as? TileAction.Dragging ?: return
        // The tile stays drawn where it was dropped until the save lands, so it doesn't jump back.
        save(undoLed = null, endsTileAction = drag) { layoutRepo.update { it.withHoldMoved(drag.holdId, drag.x, drag.y) } }
    }

    /** Held and let go without dragging: open the tile's menu. */
    fun releaseTile(holdId: Int) {
        if (savedHold(holdId) == null) return
        local.update { it.copy(tileAction = TileAction.Menu(holdId)) }
    }

    /** The menu or dialog was dismissed, or the drag was interrupted: nothing changes. */
    fun closeTile() = local.update { it.copy(tileAction = null) }

    fun editTileLeds() = local.update { l ->
        val menu = l.tileAction as? TileAction.Menu ?: return@update l
        l.copy(tileAction = TileAction.EditLeds(menu.holdId))
    }

    /** What's wrong with [text] as the edited tile's LEDs, shown under the field; null if it can be saved. */
    fun tileLedsProblem(text: String): String? {
        val holdId = local.value.tileAction?.holdId ?: return null
        val layout = layoutRepo.state.value.layoutOrNull ?: return null
        return tileLedsProblem(layout, holdId, text)
    }

    /**
     * Change LEDs: the tile keeps its spot and gets the LEDs in [text]. An LED that's already in
     * another tile, or Hidden, is refused.
     */
    fun saveTileLeds(text: String) {
        val action = local.value.tileAction as? TileAction.EditLeds ?: return
        val layout = layoutRepo.state.value.layoutOrNull ?: return
        tileLedsProblem(layout, action.holdId, text)?.let { return show(it) }
        val leds = parseLeds(text)?.distinct()?.sorted() ?: return
        local.update { it.copy(tileAction = null) }
        if (leds == savedHold(action.holdId)?.leds) return
        save(undoLed = uiState.value.current, message = "Tile changed to ${ledsText(leds)}") {
            layoutRepo.update { it.withHoldLeds(action.holdId, leds) }
        }
    }

    /** Delete tile: its LEDs become unmapped. */
    fun deleteTile() {
        val action = local.value.tileAction as? TileAction.Menu ?: return
        val hold = savedHold(action.holdId) ?: return
        local.update { it.copy(tileAction = null) }
        save(undoLed = uiState.value.current, message = "Deleted the tile with ${ledsText(hold.leds)}") {
            layoutRepo.update { it.withoutHold(hold.id) }
        }
    }

    private fun savedHold(holdId: Int): Hold? = layoutRepo.state.value.layoutOrNull?.holds?.firstOrNull { it.id == holdId }

    // ---- Undo and messages ----

    /** Undo from the snackbar: only if nothing else has changed the layout since. */
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

    /**
     * Moves on after an action to the next LED that isn't Hidden or Unused (§12.1), so LEDs that
     * stay off are skipped. Prev and Next still stop on every LED. At the end it stays and says so.
     */
    private fun advanceFrom(led: Int) {
        val layout = uiState.value.layout ?: return
        val off = layout.hiddenLeds.toHashSet().apply { addAll(layout.unusedLeds) }
        val next = (led + 1 until layout.ledCount).firstOrNull { it !in off }
        when {
            next != null -> local.update { if ((it.current ?: led) == led) it.copy(current = next) else it }
            led >= layout.ledCount - 1 -> show("That was the last LED (LED $led).")
            else -> show("That was the last LED to map. The LEDs after LED $led are Hidden or Unused.")
        }
    }

    /**
     * Runs [change]. A [message] is shown once it has saved, with Undo if [undoLed] is set (the LED
     * to go back to). [endsTileAction] is cleared once the save is done, unless something else
     * has replaced it by then.
     */
    private fun save(
        undoLed: Int?,
        failedLed: Int? = null,
        message: String? = null,
        endsTileAction: TileAction? = null,
        change: suspend () -> LayoutRepository.UpdateResult,
    ) {
        val before = layoutRepo.state.value.layoutOrNull
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = change()
            local.update { l ->
                l.copy(saving = false, tileAction = if (endsTileAction != null && l.tileAction == endsTileAction) null else l.tileAction)
            }
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
            LedAssignment.Unused -> "Unused"
            is LedAssignment.InHold -> {
                val others = assignment.hold.leds - led
                if (others.isEmpty()) "Placed" else "Paired with ${ledsText(others)}"
            }
        }

        /** "LED 37" or "LEDs 37, 38". */
        fun ledsText(leds: List<Int>): String = (if (leds.size == 1) "LED " else "LEDs ") + leds.joinToString(", ")

        /** LED numbers separated by commas or spaces, e.g. "37, 38"; null if any part isn't a number. */
        fun parseLeds(text: String): List<Int>? {
            val parts = text.split(',', ' ', ';').filter { it.isNotBlank() }
            return parts.map { it.trim().toIntOrNull() ?: return null }
        }

        fun tileLedsProblem(layout: WallLayout, holdId: Int, text: String): String? {
            val leds = parseLeds(text) ?: return "Enter LED numbers separated by commas, e.g. 37, 38."
            return layout.holdLedsProblem(holdId, leds)
        }

        fun offListProblem(layout: WallLayout, list: OffList, text: String): String? {
            val leds = parseLedList(text) ?: return "Enter LED numbers separated by commas, and ranges like 0–40."
            return layout.offListProblem(list, leds)
        }

        fun hiddenLockedText(led: Int): String =
            "LED $led is on the Hidden list, so it can't be used. To use it, take it out with Edit Hidden list."

        /** "no LEDs", "1 LED", "74 LEDs". */
        fun countText(count: Int): String = when (count) {
            0 -> "no LEDs"
            1 -> "1 LED"
            else -> "$count LEDs"
        }
    }
}
