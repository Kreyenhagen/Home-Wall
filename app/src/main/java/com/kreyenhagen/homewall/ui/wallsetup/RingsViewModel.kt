package com.kreyenhagen.homewall.ui.wallsetup

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.MappingSummary
import com.kreyenhagen.homewall.data.layout.RingShape
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.layout.hasOwnRing
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.layout.ringShape
import com.kreyenhagen.homewall.data.layout.withRing
import com.kreyenhagen.homewall.data.layout.withRingAndPositionOf
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.led.LedEntry
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.ui.components.UserMessage
import com.kreyenhagen.homewall.ui.wall.RingGeometry
import com.kreyenhagen.homewall.ui.wall.RingHandle
import com.kreyenhagen.homewall.ui.wall.WallPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RingsUiState(
    val loaded: Boolean = false,
    /** What to draw: the saved layout, with a ring being changed shown as it is now. */
    val layout: WallLayout? = null,
    val layoutProblems: List<String> = emptyList(),
    val image: ImageBitmap? = null,
    /** The placeholder is active: there's nothing to size until mapping starts. */
    val needsMapping: Boolean = false,
    /** LEDs still to map; the tab works anyway, with a reminder. */
    val unmapped: Int = 0,
    val selected: Hold? = null,
    /** e.g. "x 56.0, y 27.0 in (Main)": the selected ring's center, which is the hold's position. */
    val selectedPosition: String = "",
    /** The selected hold's ring as it is now. */
    val shape: RingShape? = null,
    /** The selected hold has its own ring, rather than the default from Settings. */
    val hasOwnRing: Boolean = false,
    /** The selected ring was changed or moved since it was selected, so Revert can undo that. */
    val canRevert: Boolean = false,
    /** Every hold's ring: white, with the selected one highlighted. */
    val rings: Map<Int, Color> = emptyMap(),
    val ringSizeIn: Float = AppSettings().ringSizeIn,
    /** The selected hold's LEDs in the identify slot, or nothing. */
    val ledFrame: LedFrame = LedFrame.OFF,
    val message: UserMessage? = null,
)

/**
 * Wall Setup → Rings (§12.4): size and place each hold's ring. The tiles are hidden and every ring
 * is shown. Tap one to select it, then drag its handles or use the sliders to change its width,
 * height and rotation, or drag its center to move it. The center is the hold's position, so moving
 * the ring moves the hold's tile in Map LEDs and Review too. Every change is saved when the finger
 * lifts.
 */
class RingsViewModel(
    private val layoutRepo: LayoutRepository,
    photo: Flow<WallPhotoState>,
    settings: Flow<AppSettings>,
) : ViewModel() {

    private data class Local(
        val selectedId: Int? = null,
        /** The selected hold as it was when selected, for Revert. */
        val original: Hold? = null,
        /** A hold whose ring is being changed or moved (by a handle or a slider), shown but not saved yet. */
        val draft: Hold? = null,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var defaultRadiusIn = AppSettings().ringSizeIn

    val uiState: StateFlow<RingsUiState> =
        combine(layoutRepo.state, photo, settings, local) { layoutState, p, s, l ->
            if (layoutState is LayoutState.Loading) return@combine RingsUiState()
            defaultRadiusIn = s.ringSizeIn
            val saved = layoutState.layoutOrNull
                ?: return@combine RingsUiState(loaded = true, layoutProblems = (layoutState as? LayoutState.Invalid)?.problems.orEmpty())
            val draft = l.draft?.takeIf { d -> saved.holds.any { it.id == d.id } }
            val shown = draft?.let(saved::withRingAndPositionOf) ?: saved
            val selected = if (saved.placeholder) null else shown.holds.firstOrNull { it.id == l.selectedId }
            val savedSelected = selected?.let { sel -> saved.holds.firstOrNull { it.id == sel.id } }
            RingsUiState(
                loaded = true,
                layout = shown,
                image = p.rectified,
                needsMapping = saved.placeholder,
                unmapped = MappingSummary.of(saved).unmapped,
                selected = selected,
                selectedPosition = selected?.let { ReviewViewModel.positionText(shown, it) }.orEmpty(),
                shape = selected?.ringShape(s.ringSizeIn),
                hasOwnRing = selected?.hasOwnRing == true,
                canRevert = savedSelected != null && l.original != null && !samePlaceAndRing(savedSelected, l.original),
                rings = if (saved.placeholder) emptyMap() else shown.holds.associate { it.id to (if (it.id == selected?.id) SELECTED else RING) },
                ringSizeIn = s.ringSizeIn,
                ledFrame = selected?.let { h -> LedFrame.of(h.leds.map { LedEntry(it, Palette.SLOT_CURSOR) }) } ?: LedFrame.OFF,
                message = l.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RingsUiState())

    // ---- Selecting ----

    fun tapHold(holdId: Int) {
        val hold = savedHold(holdId) ?: return
        saveDraft() // a change that wasn't finished, so selecting another ring doesn't lose it
        if (local.value.selectedId == holdId) return
        local.update { it.copy(selectedId = holdId, original = hold) }
    }

    fun tapEmpty() {
        saveDraft()
        local.update { it.copy(selectedId = null, original = null) }
    }

    // ---- Changing the selected ring ----

    /**
     * A handle was dragged to [point] (wall inches). The center handle moves the ring and the hold
     * with it; the others change its shape. Saved when the finger lifts ([endHandleDrag]).
     */
    fun dragHandle(handle: RingHandle, point: WallPoint) = changeSelected { hold ->
        val wall = layoutRepo.state.value.layoutOrNull?.wall
        if (handle == RingHandle.CENTER) {
            if (wall == null) return@changeSelected hold
            // Kept on the wall, like moving a tile.
            hold.copy(x = point.x.coerceIn(0f, wall.widthIn), y = point.y.coerceIn(0f, wall.heightIn))
        } else {
            hold.withRing(RingGeometry.dragged(hold.ringShape(defaultRadiusIn), hold.x, hold.y, handle, point))
        }
    }

    fun endHandleDrag() = saveDraft()

    /** The drag was interrupted (e.g. by a pinch): the ring goes back to how it was. */
    fun cancelHandleDrag() = local.update { it.copy(draft = null) }

    /** The Width slider, in inches across. Saved when the slider is let go ([endSliderChange]). */
    fun setWidth(widthIn: Float) = changeShape { it.copy(radiusXIn = widthIn / 2f) }

    fun setHeight(heightIn: Float) = changeShape { it.copy(radiusYIn = heightIn / 2f) }

    fun setAngle(degrees: Float) = changeShape { it.copy(angleDeg = degrees) }

    fun endSliderChange() = saveDraft()

    /** Height to match the width, and no rotation. */
    fun makeCircle() {
        val hold = currentSelected() ?: return
        save(hold.withRing(RingShape.circle(hold.ringShape(defaultRadiusIn).radiusXIn)))
    }

    /** Back to the default ring from Settings → Ring size. The ring stays where it is. */
    fun useDefault() {
        val hold = currentSelected() ?: return
        if (!hold.hasOwnRing) return
        save(hold.withRing(null), "Hold ${hold.id} uses the default ring size again")
    }

    /** Puts the selected ring back to how it was, and where it was, when it was selected. */
    fun revert() {
        val l = local.value
        val original = l.original ?: return
        if (l.selectedId != original.id) return
        save(original)
    }

    fun messageShown(id: Long) = local.update { if (it.message?.id == id) it.copy(message = null) else it }

    // ---- Saving ----

    private fun changeShape(change: (RingShape) -> RingShape) = changeSelected { hold ->
        hold.withRing(change(hold.ringShape(defaultRadiusIn)))
    }

    /** Shows [change] of the selected hold (starting from the change in progress, if any) without saving it. */
    private fun changeSelected(change: (Hold) -> Hold) {
        val saved = selectedSavedHold() ?: return
        local.update { l ->
            val current = l.draft?.takeIf { it.id == saved.id } ?: saved
            l.copy(draft = change(current))
        }
    }

    private fun saveDraft() {
        val draft = local.value.draft ?: return
        save(draft, endsDraft = draft)
    }

    /**
     * Saves [hold]'s ring and position. [endsDraft] stays drawn until the save lands, so the ring
     * doesn't jump back, unless another change has replaced it by then.
     */
    private fun save(hold: Hold, message: String? = null, endsDraft: Hold? = null) {
        viewModelScope.launch {
            val result = layoutRepo.update { it.withRingAndPositionOf(hold) }
            local.update { l ->
                val draft = if (endsDraft != null && l.draft == endsDraft) null else l.draft
                when (result) {
                    is LayoutRepository.UpdateResult.Rejected -> l.copy(draft = null, message = UserMessage(result.problems.joinToString("\n")))
                    LayoutRepository.UpdateResult.Saved -> l.copy(draft = draft, message = message?.let(::UserMessage) ?: l.message)
                }
            }
        }
    }

    private fun savedHold(holdId: Int): Hold? {
        val layout = layoutRepo.state.value.layoutOrNull ?: return null
        if (layout.placeholder) return null
        return layout.holds.firstOrNull { it.id == holdId }
    }

    private fun selectedSavedHold(): Hold? = local.value.selectedId?.let(::savedHold)

    /** The selected hold with any change in progress. */
    private fun currentSelected(): Hold? {
        val saved = selectedSavedHold() ?: return null
        return local.value.draft?.takeIf { it.id == saved.id } ?: saved
    }

    companion object {
        val RING = Color.White.copy(alpha = 0.85f)
        val SELECTED = Color(0xFFFFC400)

        /** The same position and ring fields, i.e. Revert would change nothing. */
        private fun samePlaceAndRing(a: Hold, b: Hold): Boolean =
            a.x == b.x && a.y == b.y && a.ringIn == b.ringIn && a.ringYIn == b.ringYIn && a.ringAngle == b.ringAngle
    }
}
