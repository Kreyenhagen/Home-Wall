package com.kreyenhagen.homewall.ui.editor

import android.database.SQLException
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.route.RouteRepository
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.domain.EditHistory
import com.kreyenhagen.homewall.domain.EditorRules
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteNames
import com.kreyenhagen.homewall.led.ringColor
import com.kreyenhagen.homewall.ui.routedisplay.RouteDisplayViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** A snackbar message. Each one has its own id, so the same text twice still shows twice. */
data class UserMessage(val text: String, val haptic: Boolean = false, val id: Long = nextMessageId.incrementAndGet())

private val nextMessageId = AtomicLong()

enum class EditorConfirm { DISCARD, DELETE }

/** How the editor closes. The screen turns this into navigation. */
sealed interface EditorExit {
    data class Saved(val routeId: Long) : EditorExit
    data object Deleted : EditorExit
    data object Closed : EditorExit
}

data class SaveDialogState(val name: String, val grade: Int?, val saving: Boolean = false) {
    /** Save needs a non-blank name of at most 40 characters (§8.3). */
    val canSave: Boolean get() = !saving && RouteNames.clean(name) != null
}

data class RouteEditorUiState(
    val isNew: Boolean = true,
    val loaded: Boolean = false,
    /** Editing a route that has since been deleted. */
    val notFound: Boolean = false,
    val layout: WallLayout? = null,
    val layoutProblems: List<String> = emptyList(),
    val image: ImageBitmap? = null,
    val ringSizeIn: Float = AppSettings().ringSizeIn,
    val showMarkers: Boolean = AppSettings().showHoldMarkers,
    /** The draft: holdId → role. */
    val holds: Map<Int, HoldRole> = emptyMap(),
    val rings: Map<Int, Color> = emptyMap(),
    val selectedRole: HoldRole = HoldRole.START,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val hasChanges: Boolean = false,
    /** Holds of the saved route that are no longer in the layout; saving drops them (§6.3). */
    val missingNote: String? = null,
    val routeName: String = "",
    val saveDialog: SaveDialogState? = null,
    val confirm: EditorConfirm? = null,
    val message: UserMessage? = null,
    val exit: EditorExit? = null,
) {
    fun count(role: HoldRole): Int = holds.values.count { it == role }

    /** Save needs at least one hold, even when only the name or grade changed (§8.4). */
    val canSave: Boolean get() = loaded && holds.isNotEmpty()

    /** The soft, non-blocking note in the Save dialog (§8.3). */
    val saveWarning: String?
        get() {
            val noStart = HoldRole.START !in holds.values
            val noFinish = HoldRole.FINISH !in holds.values
            return when {
                noStart && noFinish -> "This route has no start or finish hold."
                noStart -> "This route has no start hold."
                noFinish -> "This route has no finish hold."
                else -> null
            }
        }
}

/**
 * Route Editor, Create and Edit (§8.3, §8.4). [routeId] null means a new route. The undo
 * history lives here, so it survives rotation.
 */
class RouteEditorViewModel(
    private val routeId: Long?,
    private val routes: RouteRepository,
    layout: Flow<LayoutState>,
    photo: Flow<WallPhotoState>,
    settings: Flow<AppSettings>,
) : ViewModel() {

    private data class Local(
        val loaded: Boolean = false,
        val notFound: Boolean = false,
        /** The route as saved (Edit only). */
        val original: Route? = null,
        val initialHolds: Map<Int, HoldRole> = emptyMap(),
        val missingCount: Int = 0,
        val history: EditHistory<Map<Int, HoldRole>> = EditHistory(emptyMap()),
        val selectedRole: HoldRole = HoldRole.START,
        /** What the Save dialog starts with; kept when it's cancelled. */
        val draftName: String = "",
        val draftGrade: Int? = null,
        val dialog: SaveDialogState? = null,
        val confirm: EditorConfirm? = null,
        val message: UserMessage? = null,
        val exit: EditorExit? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<RouteEditorUiState> =
        combine(local, layout, photo, settings) { l, layoutState, photoState, s ->
            val holds = l.history.present
            RouteEditorUiState(
                isNew = routeId == null,
                loaded = l.loaded,
                notFound = l.notFound,
                layout = (layoutState as? LayoutState.Ready)?.layout,
                layoutProblems = (layoutState as? LayoutState.Invalid)?.problems.orEmpty(),
                image = photoState.rectified,
                ringSizeIn = s.ringSizeIn,
                showMarkers = s.showHoldMarkers,
                holds = holds,
                rings = holds.mapValues { it.value.ringColor },
                selectedRole = l.selectedRole,
                canUndo = l.history.canUndo,
                canRedo = l.history.canRedo,
                hasChanges = holds != l.initialHolds,
                missingNote = RouteDisplayViewModel.missingHoldsNote(l.missingCount)
                    ?.let { "$it. Saving removes ${if (l.missingCount == 1) "it" else "them"} from the route." },
                routeName = l.original?.name.orEmpty(),
                saveDialog = l.dialog,
                confirm = l.confirm,
                message = l.message,
                exit = l.exit,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteEditorUiState(isNew = routeId == null))

    init {
        viewModelScope.launch {
            val layoutState = layout.first { it !is LayoutState.Loading }
            if (routeId == null) {
                local.update { it.copy(loaded = true) }
                return@launch
            }
            val route = routes.get(routeId)
            if (route == null) {
                local.update { it.copy(loaded = true, notFound = true) }
                return@launch
            }
            val layoutIds = (layoutState as? LayoutState.Ready)?.layout?.holds?.mapTo(HashSet()) { it.id }
            val (present, missing) = route.holds.partition { layoutIds == null || it.holdId in layoutIds }
            val initial = present.associate { it.holdId to it.role }
            local.update {
                it.copy(
                    loaded = true,
                    original = route,
                    initialHolds = initial,
                    missingCount = missing.size,
                    // History starts at the saved route, so undo can't go further back (§8.4).
                    history = EditHistory(initial),
                    draftName = route.name,
                    draftGrade = route.grade,
                )
            }
        }
    }

    // ---- Editing ----

    fun onTapHold(holdId: Int) = local.update { l ->
        if (!l.loaded || l.notFound) return@update l
        when (val result = EditorRules.tap(l.history.present, holdId, l.selectedRole)) {
            is EditorRules.TapResult.Changed -> l.copy(history = l.history.push(result.holds))
            is EditorRules.TapResult.LimitReached -> l.copy(message = UserMessage(limitMessage(result.role), haptic = true))
        }
    }

    fun selectRole(role: HoldRole) = local.update { it.copy(selectedRole = role) }

    fun undo() = local.update { it.copy(history = it.history.undo()) }

    fun redo() = local.update { it.copy(history = it.history.redo()) }

    fun messageShown(id: Long) = local.update { if (it.message?.id == id) it.copy(message = null) else it }

    // ---- Save ----

    fun openSaveDialog() = local.update {
        if (!it.loaded || it.history.present.isEmpty()) it else it.copy(dialog = SaveDialogState(it.draftName, it.draftGrade))
    }

    fun setName(name: String) = local.update { l ->
        l.copy(dialog = l.dialog?.copy(name = name.take(RouteNames.MAX_LENGTH)))
    }

    fun setGrade(grade: Int?) = local.update { l -> l.copy(dialog = l.dialog?.copy(grade = grade)) }

    fun dismissSaveDialog() = local.update { l ->
        val dialog = l.dialog ?: return@update l
        if (dialog.saving) l else l.copy(dialog = null, draftName = dialog.name, draftGrade = dialog.grade)
    }

    fun save() {
        val l = local.value
        val dialog = l.dialog ?: return
        val name = RouteNames.clean(dialog.name) ?: return
        if (dialog.saving || l.history.present.isEmpty()) return
        local.update { it.copy(dialog = dialog.copy(saving = true)) }

        viewModelScope.launch {
            val holds = EditorRules.toRouteHolds(l.history.present)
            val original = l.original
            val result = try {
                if (original == null) {
                    routes.create(name, dialog.grade, holds)
                } else {
                    original.id.takeIf { routes.update(original.id, name, dialog.grade, holds) }
                }
            } catch (e: SQLException) {
                local.update { it.copy(dialog = dialog, message = UserMessage("Couldn't save the route: ${e.message}")) }
                return@launch
            }
            local.update {
                if (result == null) {
                    it.copy(dialog = null, notFound = true, message = UserMessage("This route was deleted, so it can't be saved."))
                } else {
                    it.copy(dialog = null, exit = EditorExit.Saved(result))
                }
            }
        }
    }

    // ---- Leaving ----

    /** Back: ask first if there are unsaved changes (§8.3). */
    fun onBack() = local.update {
        if (it.history.present != it.initialHolds && !it.notFound) it.copy(confirm = EditorConfirm.DISCARD) else it.copy(exit = EditorExit.Closed)
    }

    fun requestDelete() = local.update { if (it.original == null) it else it.copy(confirm = EditorConfirm.DELETE) }

    fun dismissConfirm() = local.update { it.copy(confirm = null) }

    fun confirm() {
        when (local.value.confirm) {
            EditorConfirm.DISCARD -> local.update { it.copy(confirm = null, exit = EditorExit.Closed) }
            EditorConfirm.DELETE -> {
                val id = local.value.original?.id ?: return
                local.update { it.copy(confirm = null) }
                viewModelScope.launch {
                    try {
                        routes.delete(id)
                        local.update { it.copy(exit = EditorExit.Deleted) }
                    } catch (e: SQLException) {
                        local.update { it.copy(message = UserMessage("Couldn't delete the route: ${e.message}")) }
                    }
                }
            }
            null -> Unit
        }
    }

    companion object {
        fun limitMessage(role: HoldRole): String = when (role) {
            HoldRole.START -> "Max ${EditorRules.MAX_START} start holds"
            HoldRole.FINISH -> "Max ${EditorRules.MAX_FINISH} finish holds"
            HoldRole.MIDDLE -> "Too many middle holds"
        }
    }
}
