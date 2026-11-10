package com.kreyenhagen.homewall.ui.wallsetup

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.LayoutFormat
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.MappingSummary
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.layout.withHoldMoved
import com.kreyenhagen.homewall.data.layout.withLedRemovedFromHold
import com.kreyenhagen.homewall.data.layout.withoutHold
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.led.LedEntry
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.ui.components.UserMessage
import com.kreyenhagen.homewall.ui.wall.LedPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** A hold being dragged to a new spot (§12.2 Move), not saved yet. */
data class MoveDraft(val holdId: Int, val x: Float, val y: Float)

data class ReviewUiState(
    val loaded: Boolean = false,
    /** What to draw: the saved layout, with a hold being moved shown at its new spot. */
    val layout: WallLayout? = null,
    val layoutProblems: List<String> = emptyList(),
    val image: ImageBitmap? = null,
    val summary: MappingSummary? = null,
    val selected: Hold? = null,
    /** e.g. "x 56.0, y 27.0 in (Main)". */
    val selectedPosition: String = "",
    val lightAll: Boolean = false,
    val moving: Boolean = false,
    val confirmDelete: Boolean = false,
    val rings: Map<Int, Color> = emptyMap(),
    val ringSizeIn: Float = AppSettings().ringSizeIn,
    val ledPreview: Map<Int, Color> = emptyMap(),
    val ledFrame: LedFrame = LedFrame.OFF,
    val message: UserMessage? = null,
)

/**
 * Wall Setup → Review (§12.2): check every hold against the real wall. Tapping a hold lights its
 * LEDs; "Light all holds" lights them all dimly; holds can be moved, trimmed or deleted.
 */
class ReviewViewModel(
    private val layoutRepo: LayoutRepository,
    photo: Flow<WallPhotoState>,
    settings: Flow<AppSettings>,
    sentFrame: Flow<LedFrame?> = flowOf(null),
    private val exportLayout: suspend (Uri, LayoutFormat) -> String? = { _, _ -> null },
) : ViewModel() {

    private data class Local(
        val selectedId: Int? = null,
        val lightAll: Boolean = false,
        val move: MoveDraft? = null,
        val confirmDelete: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<ReviewUiState> =
        combine(layoutRepo.state, photo, settings, sentFrame, local) { layoutState, p, s, sent, l ->
            if (layoutState is LayoutState.Loading) return@combine ReviewUiState()
            val saved = layoutState.layoutOrNull
                ?: return@combine ReviewUiState(loaded = true, layoutProblems = (layoutState as? LayoutState.Invalid)?.problems.orEmpty())
            val move = l.move
            val shown = if (move != null) saved.withHoldMoved(move.holdId, move.x, move.y) else saved
            val selected = shown.holds.firstOrNull { it.id == l.selectedId }
            ReviewUiState(
                loaded = true,
                layout = shown,
                image = p.rectified,
                summary = MappingSummary.of(saved),
                selected = selected,
                selectedPosition = selected?.let { positionText(shown, it) }.orEmpty(),
                lightAll = l.lightAll,
                moving = move != null && selected != null,
                confirmDelete = l.confirmDelete && selected != null,
                rings = selected?.let { mapOf(it.id to Color.White) }.orEmpty(),
                ringSizeIn = s.ringSizeIn,
                ledPreview = if (s.showLedPreview) LedPreview.dots(sent, shown) else emptyMap(),
                ledFrame = frameFor(shown, selected, l.lightAll),
                message = l.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    fun tapHold(holdId: Int) = local.update { if (it.move != null) it else it.copy(selectedId = holdId, confirmDelete = false) }

    fun tapEmpty() = local.update { if (it.move != null) it else it.copy(selectedId = null, confirmDelete = false) }

    fun setLightAll(on: Boolean) = local.update { it.copy(lightAll = on) }

    // ---- Move (drag the marker) ----

    fun startMove() {
        val hold = uiState.value.selected ?: return
        local.update { it.copy(move = MoveDraft(hold.id, hold.x, hold.y)) }
    }

    fun dragBy(dxIn: Float, dyIn: Float) {
        val wall = layoutRepo.state.value.layoutOrNull?.wall ?: return
        local.update { l ->
            val move = l.move ?: return@update l
            l.copy(move = move.copy(x = (move.x + dxIn).coerceIn(0f, wall.widthIn), y = (move.y + dyIn).coerceIn(0f, wall.heightIn)))
        }
    }

    fun saveMove() {
        val move = local.value.move ?: return
        local.update { it.copy(move = null) }
        save("Hold ${move.holdId} moved") { it.withHoldMoved(move.holdId, move.x, move.y) }
    }

    fun cancelMove() = local.update { it.copy(move = null) }

    // ---- Trim and delete ----

    fun removeLed(led: Int) {
        val hold = uiState.value.selected ?: return
        if (hold.leds.size < 2) return
        save("LED $led removed from hold ${hold.id}. It's unmapped now.") { it.withLedRemovedFromHold(hold.id, led) }
    }

    fun requestDelete() = local.update { if (it.selectedId == null) it else it.copy(confirmDelete = true) }

    fun dismissDelete() = local.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        val hold = uiState.value.selected ?: return
        local.update { it.copy(confirmDelete = false, selectedId = null) }
        val leds = hold.leds.joinToString(", ")
        save("Hold ${hold.id} deleted. LED${if (hold.leds.size > 1) "s" else ""} $leds ${if (hold.leds.size > 1) "are" else "is"} unmapped now.") {
            it.withoutHold(hold.id)
        }
    }

    // ---- Export (§12.2) ----

    fun export(uri: Uri, format: LayoutFormat) {
        viewModelScope.launch {
            val problem = exportLayout(uri, format)
            show(problem ?: "Layout exported")
        }
    }

    fun messageShown(id: Long) = local.update { if (it.message?.id == id) it.copy(message = null) else it }

    private fun save(success: String, change: (WallLayout) -> WallLayout) {
        viewModelScope.launch {
            when (val result = layoutRepo.update(change)) {
                LayoutRepository.UpdateResult.Saved -> show(success)
                is LayoutRepository.UpdateResult.Rejected -> show(result.problems.joinToString("\n"))
            }
        }
    }

    private fun show(text: String) = local.update { it.copy(message = UserMessage(text)) }

    companion object {
        /**
         * What the wall shows in Review (§9.2): the selected hold in the identify slot, every hold
         * dimly when "Light all holds" is on, or nothing.
         */
        fun frameFor(layout: WallLayout, selected: Hold?, lightAll: Boolean): LedFrame {
            val entries = mutableListOf<LedEntry>()
            selected?.leds?.forEach { entries += LedEntry(it, Palette.SLOT_CURSOR) }
            if (lightAll) layout.holds.forEach { hold -> hold.leds.forEach { entries += LedEntry(it, Palette.SLOT_ALL_HOLDS) } }
            return LedFrame.of(entries) // the selected hold's entries come first, so they win
        }

        fun positionText(layout: WallLayout, hold: Hold): String {
            val panel = layout.wall.panels.getOrNull(layout.wall.panelIndexAt(hold.y))?.name.orEmpty()
            return String.format(Locale.getDefault(), "x %.1f, y %.1f in (%s)", hold.x, hold.y, panel)
        }
    }
}
