package com.kreyenhagen.homewall.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kreyenhagen.homewall.R
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.domain.EditorRules
import com.kreyenhagen.homewall.domain.Grades
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.domain.RouteNames
import com.kreyenhagen.homewall.led.ringColor
import com.kreyenhagen.homewall.ui.appViewModel
import com.kreyenhagen.homewall.ui.components.BackButton
import com.kreyenhagen.homewall.ui.components.LayoutProblems
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import com.kreyenhagen.homewall.ui.wall.WallView

/** Everything the editor's controls can do, so the content composable stays stateless. */
class EditorActions(
    val onBack: () -> Unit,
    val onTapHold: (Int) -> Unit,
    val onSelectRole: (HoldRole) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onOpenSave: () -> Unit,
    val onNameChange: (String) -> Unit,
    val onGradeChange: (Int?) -> Unit,
    val onSave: () -> Unit,
    val onDismissSave: () -> Unit,
    val onRequestDelete: () -> Unit,
    val onConfirm: () -> Unit,
    val onDismissConfirm: () -> Unit,
) {
    companion object {
        val None = EditorActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** [routeId] null means a new route. */
@Composable
fun RouteEditorScreen(
    routeId: Long?,
    onBack: () -> Unit,
    onSaved: (routeId: Long) -> Unit,
    onDeleted: () -> Unit,
) {
    val vm = appViewModel {
        RouteEditorViewModel(
            routeId = routeId,
            routes = it.routeRepository,
            layout = it.layoutRepository.state,
            photo = it.wallPhotoRepository.state,
            settings = it.settingsRepository.settings,
        )
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    // Every Back goes through the ViewModel, which asks first if there are unsaved changes.
    BackHandler(enabled = state.exit == null) { vm.onBack() }

    LaunchedEffect(state.exit) {
        when (val exit = state.exit) {
            is EditorExit.Saved -> onSaved(exit.routeId)
            EditorExit.Deleted -> onDeleted()
            EditorExit.Closed -> onBack()
            null -> Unit
        }
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        if (message.haptic) haptics.performHapticFeedback(HapticFeedbackType.Reject)
        // A newer message cancels this one and takes its place.
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message.text)
        vm.messageShown(message.id)
    }

    RouteEditorContent(
        state = state,
        snackbarHostState = snackbar,
        actions = remember(vm) {
            EditorActions(
                onBack = vm::onBack,
                onTapHold = vm::onTapHold,
                onSelectRole = vm::selectRole,
                onUndo = vm::undo,
                onRedo = vm::redo,
                onOpenSave = vm::openSaveDialog,
                onNameChange = vm::setName,
                onGradeChange = vm::setGrade,
                onSave = vm::save,
                onDismissSave = vm::dismissSaveDialog,
                onRequestDelete = vm::requestDelete,
                onConfirm = vm::confirm,
                onDismissConfirm = vm::dismissConfirm,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteEditorContent(state: RouteEditorUiState, snackbarHostState: SnackbarHostState, actions: EditorActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.isNew) "New route" else "Edit route",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton(actions.onBack) },
                actions = { if (!state.isNew) OverflowMenu(onDelete = actions.onRequestDelete, enabled = state.loaded && !state.notFound) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val layout = state.layout
            val landscape = maxWidth > maxHeight
            when {
                !state.loaded -> Unit
                state.notFound -> Message("This route no longer exists.")
                layout == null -> LayoutProblems(state.layoutProblems)
                landscape -> Row(Modifier.fillMaxSize()) {
                    EditorWall(state, layout, actions, Modifier.weight(1f).fillMaxHeight())
                    Surface(tonalElevation = 2.dp, modifier = Modifier.width(420.dp).fillMaxHeight()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            state.missingNote?.let { MissingNote(it) }
                            RoleSelector(state, actions.onSelectRole, Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UndoRedo(state, actions)
                                Spacer(Modifier.weight(1f))
                                SaveButton(state, actions)
                            }
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    EditorWall(state, layout, actions, Modifier.weight(1f).fillMaxWidth())
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            state.missingNote?.let {
                                MissingNote(it)
                                Spacer(Modifier.height(8.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RoleSelector(state, actions.onSelectRole, Modifier.weight(1f))
                                Spacer(Modifier.width(16.dp))
                                UndoRedo(state, actions)
                                Spacer(Modifier.width(8.dp))
                                SaveButton(state, actions)
                            }
                        }
                    }
                }
            }
        }
    }

    state.saveDialog?.let { SaveRouteDialog(it, isNew = state.isNew, warning = state.saveWarning, actions = actions) }

    when (state.confirm) {
        EditorConfirm.DISCARD -> ConfirmDialog(
            title = if (state.isNew) "Discard this route?" else "Discard your changes?",
            text = if (state.isNew) "The holds you picked won't be saved." else "The route stays as it was.",
            confirmLabel = "Discard",
            actions = actions,
        )
        EditorConfirm.DELETE -> ConfirmDialog(
            title = "Delete “${state.routeName}”?",
            text = "This can't be undone.",
            confirmLabel = "Delete",
            actions = actions,
        )
        null -> Unit
    }
}

@Composable
private fun EditorWall(state: RouteEditorUiState, layout: WallLayout, actions: EditorActions, modifier: Modifier) {
    WallView(
        layout = layout,
        image = state.image,
        modifier = modifier,
        rings = state.rings,
        ringSizeIn = state.ringSizeIn,
        showMarkers = state.showMarkers,
        zoomEnabled = true,
        onTapHold = actions.onTapHold,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleSelector(state: RouteEditorUiState, onSelect: (HoldRole) -> Unit, modifier: Modifier) {
    val roles = HoldRole.entries
    SingleChoiceSegmentedButtonRow(modifier) {
        roles.forEachIndexed { index, role ->
            SegmentedButton(
                selected = state.selectedRole == role,
                onClick = { onSelect(role) },
                shape = SegmentedButtonDefaults.itemShape(index, roles.size),
                icon = { Box(Modifier.size(14.dp).background(role.ringColor, CircleShape)) },
                label = { Text(roleLabel(role, state.count(role)), maxLines = 1) },
            )
        }
    }
}

/** "Start (1/2)", "Middle (4)", "Finish (0/2)" (§8.3). */
private fun roleLabel(role: HoldRole, count: Int): String {
    val name = when (role) {
        HoldRole.START -> "Start"
        HoldRole.MIDDLE -> "Middle"
        HoldRole.FINISH -> "Finish"
    }
    val limit = EditorRules.limitFor(role)
    return if (limit == null) "$name ($count)" else "$name ($count/$limit)"
}

@Composable
private fun UndoRedo(state: RouteEditorUiState, actions: EditorActions) {
    Row {
        IconButton(onClick = actions.onUndo, enabled = state.canUndo) {
            Icon(painterResource(R.drawable.ic_undo), contentDescription = "Undo")
        }
        IconButton(onClick = actions.onRedo, enabled = state.canRedo) {
            Icon(painterResource(R.drawable.ic_redo), contentDescription = "Redo")
        }
    }
}

@Composable
private fun SaveButton(state: RouteEditorUiState, actions: EditorActions) {
    Button(onClick = actions.onOpenSave, enabled = state.canSave) { Text("Save") }
}

@Composable
private fun MissingNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverflowMenu(onDelete: () -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Delete route") },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirmLabel: String, actions: EditorActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissConfirm,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = actions.onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = actions.onDismissConfirm) { Text("Cancel") } },
    )
}

/** §8.3: name (required, ≤ 40 characters, autofocused), grade (default Project), soft warning. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveRouteDialog(dialog: SaveDialogState, isNew: Boolean, warning: String?, actions: EditorActions) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = actions.onDismissSave,
        title = { Text(if (isNew) "Save route" else "Save changes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = dialog.name,
                    onValueChange = actions.onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    supportingText = { Text("${dialog.name.length} / ${RouteNames.MAX_LENGTH}") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                GradePicker(dialog.grade, actions.onGradeChange)
                if (warning != null) {
                    HorizontalDivider()
                    Text("$warning You can still save it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = actions.onSave, enabled = dialog.canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = actions.onDismissSave, enabled = !dialog.saving) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradePicker(grade: Int?, onChange: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options: List<Int?> = listOf<Int?>(null) + (Grades.MIN..Grades.MAX).toList()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = Grades.label(grade),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Grade") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(Grades.label(option)) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun previewState(): RouteEditorUiState {
    val holds = mapOf(5 to HoldRole.START, 27 to HoldRole.MIDDLE, 60 to HoldRole.MIDDLE, 148 to HoldRole.FINISH)
    return RouteEditorUiState(
        isNew = false,
        loaded = true,
        layout = PlaceholderLayout.create(),
        holds = holds,
        rings = holds.mapValues { it.value.ringColor },
        selectedRole = HoldRole.MIDDLE,
        canUndo = true,
        missingNote = "1 hold no longer exists. Saving removes it from the route.",
    )
}

@TabletPreviews
@Composable
private fun RouteEditorPreview() {
    HomeWallTheme { RouteEditorContent(previewState(), remember { SnackbarHostState() }, EditorActions.None) }
}

@TabletPreviews
@Composable
private fun SaveDialogPreview() {
    HomeWallTheme {
        RouteEditorContent(
            previewState().copy(saveDialog = SaveDialogState("Pinch and pray", 5), holds = mapOf(27 to HoldRole.MIDDLE)),
            remember { SnackbarHostState() },
            EditorActions.None,
        )
    }
}
