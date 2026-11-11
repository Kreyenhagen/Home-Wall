package com.kreyenhagen.homewall.ui.wallsetup

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.WallGeometry
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.photo.AlignmentEdge
import com.kreyenhagen.homewall.data.photo.NormPoint
import com.kreyenhagen.homewall.data.photo.PhotoAlignment
import com.kreyenhagen.homewall.data.photo.PhotoResult
import com.kreyenhagen.homewall.data.photo.WallPhotoRepository
import com.kreyenhagen.homewall.data.photo.WallRectifier
import com.kreyenhagen.homewall.data.settings.SettingsRepository
import com.kreyenhagen.homewall.ui.wall.WallPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class WallSetupStep(val title: String) {
    PHOTO("Photo"),
    ALIGN("Align"),
    MAP_LEDS("Map LEDs"),
    RINGS("Rings"),
    REVIEW("Review"),
}

enum class AlignMode {
    /** Dragging the six handles on the raw photo. */
    EDIT_POINTS,

    /** Checking the straightened result with hold markers on it. */
    RESULT,
}

/** The Align step's unsaved edits: handle positions and the size fields as typed. */
data class AlignDraft(
    val edges: List<AlignmentEdge>,
    val widthText: String,
    val panelTexts: List<String>,
)

data class WallSetupUiState(
    val step: WallSetupStep = WallSetupStep.PHOTO,
    val layout: WallLayout? = null,
    val layoutProblems: List<String> = emptyList(),
    val hasPhoto: Boolean = false,
    /** The original photo, for the Photo and Align steps. */
    val photo: ImageBitmap? = null,
    val rectified: ImageBitmap? = null,
    val working: Boolean = false,
    val alignMode: AlignMode = AlignMode.RESULT,
    val hasSavedAlignment: Boolean = false,
    val draft: AlignDraft? = null,
    val sizesChanged: Boolean = false,
    val edgeNames: List<String> = emptyList(),
    val selectedHoldId: Int? = null,
    val tapInfo: String? = null,
    val ringSizeIn: Float = 3.5f,
    val message: String? = null,
)

class WallSetupViewModel(
    private val layoutRepo: LayoutRepository,
    private val photoRepo: WallPhotoRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    private data class Local(
        val step: WallSetupStep = WallSetupStep.PHOTO,
        val photo: ImageBitmap? = null,
        val alignMode: AlignMode = AlignMode.RESULT,
        val draft: AlignDraft? = null,
        val selectedHoldId: Int? = null,
        val tapInfo: String? = null,
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<WallSetupUiState> =
        combine(layoutRepo.state, photoRepo.state, settingsRepo.settings, local) { layoutState, photo, settings, l ->
            val layout = layoutState.layoutOrNull
            WallSetupUiState(
                step = l.step,
                layout = layout,
                layoutProblems = (layoutState as? LayoutState.Invalid)?.problems.orEmpty(),
                hasPhoto = photo.hasPhoto,
                photo = l.photo,
                rectified = photo.rectified,
                working = photo.working,
                alignMode = if (l.photo == null) AlignMode.RESULT else l.alignMode,
                hasSavedAlignment = photo.alignment != null,
                draft = l.draft ?: layout?.let { draftFor(it.wall, photo.alignment) },
                sizesChanged = layout != null && l.draft != null && sizesChanged(layout.wall, l.draft),
                edgeNames = layout?.let { edgeNames(it.wall.panels.size) }.orEmpty(),
                selectedHoldId = l.selectedHoldId,
                tapInfo = l.tapInfo,
                ringSizeIn = settings.ringSizeIn,
                message = l.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallSetupUiState())

    init {
        viewModelScope.launch {
            val photo = photoRepo.state.first { it.loaded }
            val layout = layoutRepo.state.first { it !is LayoutState.Loading }.layoutOrNull
            val rawPhoto = if (photo.hasPhoto) photoRepo.loadPhoto() else null
            local.update {
                it.copy(
                    step = if (photo.hasPhoto) WallSetupStep.ALIGN else WallSetupStep.PHOTO,
                    photo = rawPhoto,
                    alignMode = if (rawPhoto != null && photo.alignment == null) AlignMode.EDIT_POINTS else AlignMode.RESULT,
                    draft = layout?.let { l -> draftFor(l.wall, photo.alignment) },
                )
            }
        }
    }

    fun selectStep(step: WallSetupStep) = local.update { it.copy(step = step) }

    fun messageShown() = local.update { it.copy(message = null) }

    fun showMessage(message: String) = local.update { it.copy(message = message) }

    // ---- Photo ----

    fun importPhoto(uri: Uri) {
        viewModelScope.launch {
            val geometry = layoutRepo.state.value.layoutOrNull?.wall
            when (val result = photoRepo.importPhoto(uri, geometry)) {
                is PhotoResult.Failed -> showMessage(result.message)
                PhotoResult.Done -> {
                    val photo = photoRepo.loadPhoto()
                    local.update {
                        it.copy(
                            photo = photo,
                            step = WallSetupStep.ALIGN,
                            alignMode = AlignMode.EDIT_POINTS,
                            selectedHoldId = null,
                            tapInfo = null,
                        )
                    }
                }
            }
        }
    }

    // ---- Align: points ----

    fun moveHandle(edgeIndex: Int, left: Boolean, point: NormPoint) = updateDraft { draft ->
        val edges = draft.edges.toMutableList()
        val edge = edges.getOrNull(edgeIndex) ?: return@updateDraft draft
        edges[edgeIndex] = if (left) edge.copy(left = point) else edge.copy(right = point)
        draft.copy(edges = edges)
    }

    fun adjustPoints() = local.update { it.copy(alignMode = AlignMode.EDIT_POINTS) }

    fun resetPoints() = updateDraft { it.copy(edges = PhotoAlignment.defaultGuess(it.edges.size).edges) }

    /** Leaves point editing and goes back to the saved points. */
    fun cancelAdjust() {
        val saved = photoRepo.state.value.alignment
        local.update { l ->
            l.copy(
                alignMode = AlignMode.RESULT,
                draft = l.draft?.let { d -> if (saved != null) d.copy(edges = saved.edges) else d },
            )
        }
    }

    // ---- Align: sizes ----

    fun setWidthText(text: String) = updateDraft { it.copy(widthText = text) }

    fun setPanelText(index: Int, text: String) = updateDraft { draft ->
        draft.copy(panelTexts = draft.panelTexts.mapIndexed { i, t -> if (i == index) text else t })
    }

    /**
     * Saves the sizes (rescaling holds per §6.1) and, when editing points, the alignment, then
     * regenerates the straightened image (§7.1).
     */
    fun save() {
        val state = uiState.value
        val layout = state.layout ?: return showMessage("The wall layout has problems, so it can't be changed yet.")
        val draft = state.draft ?: return

        val width = parseInches(draft.widthText)
        val heights = draft.panelTexts.map(::parseInches)
        if (width == null || heights.any { it == null }) {
            return showMessage("Sizes must be numbers between $MIN_SIZE_IN and $MAX_SIZE_IN inches.")
        }
        val newGeometry = WallGeometry(width, layout.wall.panels.zip(heights) { p, h -> p.copy(heightIn = h ?: p.heightIn) })
        val photo = state.photo.takeIf { state.alignMode == AlignMode.EDIT_POINTS }
        val savePoints = photo != null
        val alignment = PhotoAlignment(draft.edges)

        // Check the points before touching anything, so a bad quad doesn't leave a half-saved setup.
        if (photo != null) {
            val plan = WallRectifier.plan(newGeometry, alignment, photo.width, photo.height)
            if (plan is WallRectifier.PlanResult.Failed) return showMessage(plan.message)
        }

        viewModelScope.launch {
            if (sizesChanged(layout.wall, draft)) {
                val result = layoutRepo.update { it.resized(width, heights.map { h -> h ?: 0f }) }
                if (result is LayoutRepository.UpdateResult.Rejected) {
                    return@launch showMessage(result.problems.joinToString("\n"))
                }
            }
            val photoResult = when {
                savePoints -> photoRepo.saveAlignment(alignment, newGeometry)
                sizesChanged(layout.wall, draft) -> photoRepo.regenerate(newGeometry)
                else -> PhotoResult.Done
            }
            when (photoResult) {
                is PhotoResult.Failed -> showMessage(photoResult.message)
                PhotoResult.Done -> {
                    val saved = layoutRepo.state.value.layoutOrNull ?: layout
                    local.update {
                        it.copy(
                            alignMode = AlignMode.RESULT,
                            draft = draftFor(saved.wall, photoRepo.state.value.alignment ?: alignment),
                            message = "Saved",
                        )
                    }
                }
            }
        }
    }

    // ---- Align: checking the result ----

    fun tapHold(holdId: Int) {
        val layout = uiState.value.layout ?: return
        val hold = layout.holds.firstOrNull { it.id == holdId } ?: return
        val panel = layout.wall.panels[layout.wall.panelIndexAt(hold.y)].name
        val leds = hold.leds.joinToString(", ")
        local.update {
            it.copy(
                selectedHoldId = holdId,
                tapInfo = "Hold ${hold.id} · LED${if (hold.leds.size > 1) "s" else ""} $leds · " +
                    "x ${inches(hold.x)}, y ${inches(hold.y)} in ($panel)",
            )
        }
    }

    fun tapEmpty(point: WallPoint) = local.update {
        it.copy(selectedHoldId = null, tapInfo = "No hold here · x ${inches(point.x)}, y ${inches(point.y)} in")
    }

    private fun updateDraft(change: (AlignDraft) -> AlignDraft) {
        val shown = uiState.value.draft
        local.update { l -> l.copy(draft = (l.draft ?: shown)?.let(change)) }
    }

    companion object {
        const val MIN_SIZE_IN = 1f
        const val MAX_SIZE_IN = 400f

        fun edgeNames(panelCount: Int): List<String> = when (panelCount) {
            2 -> listOf("Floor", "Fold", "Top")
            else -> (0..panelCount).map { i ->
                when (i) {
                    0 -> "Floor"
                    panelCount -> "Top"
                    else -> "Fold $i"
                }
            }
        }

        fun parseInches(text: String): Float? =
            text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it in MIN_SIZE_IN..MAX_SIZE_IN }

        /** 96 → "96", 15.5 → "15.5". */
        fun formatInches(value: Float): String {
            val rounded = (value * 100).roundToInt() / 100f
            return if (rounded == rounded.roundToInt().toFloat()) rounded.roundToInt().toString() else rounded.toString()
        }

        private fun inches(value: Float) = String.format(Locale.getDefault(), "%.1f", value)

        private fun draftFor(wall: WallGeometry, alignment: PhotoAlignment?) = AlignDraft(
            edges = alignment?.edges?.takeIf { it.size == wall.panels.size + 1 }
                ?: PhotoAlignment.defaultGuess(wall.panels.size + 1).edges,
            widthText = formatInches(wall.widthIn),
            panelTexts = wall.panels.map { formatInches(it.heightIn) },
        )

        private fun sizesChanged(wall: WallGeometry, draft: AlignDraft): Boolean {
            val width = parseInches(draft.widthText)
            val heights = draft.panelTexts.map(::parseInches)
            if (width == null || heights.any { it == null }) return true
            return abs(width - wall.widthIn) > 0.001f ||
                wall.panels.zip(heights).any { (p, h) -> abs((h ?: 0f) - p.heightIn) > 0.001f }
        }
    }
}
