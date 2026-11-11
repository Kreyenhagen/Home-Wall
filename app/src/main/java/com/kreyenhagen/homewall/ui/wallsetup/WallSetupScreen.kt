package com.kreyenhagen.homewall.ui.wallsetup

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kreyenhagen.homewall.data.layout.LayoutFormat
import com.kreyenhagen.homewall.ui.appViewModel
import com.kreyenhagen.homewall.ui.components.BackButton
import com.kreyenhagen.homewall.ui.components.DriveLeds
import com.kreyenhagen.homewall.ui.components.WallLeds
import com.kreyenhagen.homewall.ui.wall.HoldPressActions
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** How long Map LEDs' Undo snackbar stays up before it goes away on its own. */
private const val UNDO_SNACKBAR_MILLIS = 2_000L

@Composable
fun WallSetupScreen(onBack: () -> Unit) {
    val vm = appViewModel { WallSetupViewModel(it.layoutRepository, it.wallPhotoRepository, it.settingsRepository) }
    val mapVm = appViewModel {
        MapLedsViewModel(
            layoutRepo = it.layoutRepository,
            photo = it.wallPhotoRepository.state,
            settings = it.settingsRepository.settings,
            connection = it.ledController.state,
            sentFrame = it.ledSync.lastSent,
        )
    }
    val reviewVm = appViewModel {
        ReviewViewModel(
            layoutRepo = it.layoutRepository,
            photo = it.wallPhotoRepository.state,
            settings = it.settingsRepository.settings,
            sentFrame = it.ledSync.lastSent,
            exportLayout = it.layoutTransfer::export,
        )
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val mapState by mapVm.uiState.collectAsStateWithLifecycle()
    val ringsVm = appViewModel {
        RingsViewModel(
            layoutRepo = it.layoutRepository,
            photo = it.wallPhotoRepository.state,
            settings = it.settingsRepository.settings,
        )
    }
    val reviewState by reviewVm.uiState.collectAsStateWithLifecycle()
    val ringsState by ringsVm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val accessibility = LocalAccessibilityManager.current
    val snackbar = remember { SnackbarHostState() }

    // §9.2: Map LEDs lights the current LED, Rings the selected hold, Review the identified hold or
    // all holds. Photo and Align (and Map LEDs before mapping starts) keep the last route lit.
    DriveLeds(
        when (state.step) {
            WallSetupStep.MAP_LEDS -> mapState.ledFrame?.let(WallLeds::Frame) ?: WallLeds.LastRoute
            WallSetupStep.RINGS -> when {
                !ringsState.loaded -> null
                ringsState.needsMapping -> WallLeds.LastRoute
                else -> WallLeds.Frame(ringsState.ledFrame)
            }
            WallSetupStep.REVIEW -> if (reviewState.loaded) WallLeds.Frame(reviewState.ledFrame) else null
            else -> WallLeds.LastRoute
        },
    )

    LaunchedEffect(mapState.message) {
        val message = mapState.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val result = if (mapState.undo != null) {
            // Material keeps a snackbar with an action up until it's tapped; Undo should go away
            // on its own. Accessibility settings can ask for longer.
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                UNDO_SNACKBAR_MILLIS,
                containsText = true,
                containsControls = true,
            ) ?: UNDO_SNACKBAR_MILLIS
            withTimeoutOrNull(timeout) {
                snackbar.showSnackbar(message.text, actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
            } ?: SnackbarResult.Dismissed
        } else {
            snackbar.showSnackbar(message.text)
        }
        if (result == SnackbarResult.ActionPerformed) mapVm.undo()
        mapVm.messageShown(message.id)
    }
    LaunchedEffect(ringsState.message) {
        val message = ringsState.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message.text)
        ringsVm.messageShown(message.id)
    }
    LaunchedEffect(reviewState.message) {
        val message = reviewState.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message.text)
        reviewVm.messageShown(message.id)
    }

    val choosePhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importPhoto(uri)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) vm.importPhoto(cameraCaptureUri(context))
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LayoutFormat.JSON.mimeType)) { uri ->
        if (uri != null) reviewVm.export(uri, LayoutFormat.JSON)
    }

    WallSetupContent(
        state = state,
        snackbarHostState = snackbar,
        onBack = onBack,
        onSelectStep = vm::selectStep,
        onMessageShown = vm::messageShown,
        onTakePhoto = {
            try {
                takePhoto.launch(cameraCaptureUri(context))
            } catch (e: ActivityNotFoundException) {
                vm.showMessage("No camera app is available. Use Choose photo instead.")
            }
        },
        onChoosePhoto = {
            choosePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        alignActions = remember(vm) {
            AlignActions(
                onMoveHandle = vm::moveHandle,
                onWidthChange = vm::setWidthText,
                onPanelChange = vm::setPanelText,
                onSave = vm::save,
                onAdjustPoints = vm::adjustPoints,
                onCancelAdjust = vm::cancelAdjust,
                onResetPoints = vm::resetPoints,
                onTapHold = vm::tapHold,
                onTapEmpty = vm::tapEmpty,
            )
        },
        mapLeds = {
            MapLedsStep(
                state = mapState,
                actions = remember(mapVm) {
                    MapLedsActions(
                        onStartMapping = mapVm::startMapping,
                        onChangeLedCount = mapVm::changeLedCount,
                        onPrevious = mapVm::previous,
                        onNext = mapVm::next,
                        onJumpTo = mapVm::jumpTo,
                        onHide = mapVm::hide,
                        onUnused = mapVm::markUnused,
                        offListProblem = mapVm::offListProblem,
                        onSaveOffList = mapVm::saveOffList,
                        withKnownHidden = mapVm::withKnownHidden,
                        onTapHold = mapVm::tapHold,
                        onTapEmpty = mapVm::tapEmpty,
                        tilePress = HoldPressActions(
                            onPressed = mapVm::pressTile,
                            onDrag = mapVm::dragTile,
                            onDragEnd = mapVm::endTileDrag,
                            onReleased = mapVm::releaseTile,
                            onCancel = mapVm::closeTile,
                        ),
                        onCloseTile = mapVm::closeTile,
                        onEditTileLeds = mapVm::editTileLeds,
                        onDeleteTile = mapVm::deleteTile,
                        tileLedsProblem = mapVm::tileLedsProblem,
                        onSaveTileLeds = mapVm::saveTileLeds,
                    )
                },
            )
        },
        rings = {
            RingsStep(
                state = ringsState,
                actions = remember(ringsVm, mapVm, vm) {
                    RingsActions(
                        onTapHold = ringsVm::tapHold,
                        onTapEmpty = ringsVm::tapEmpty,
                        onDragHandle = ringsVm::dragHandle,
                        onEndHandleDrag = ringsVm::endHandleDrag,
                        onCancelHandleDrag = ringsVm::cancelHandleDrag,
                        onWidth = ringsVm::setWidth,
                        onHeight = ringsVm::setHeight,
                        onAngle = ringsVm::setAngle,
                        onSliderDone = ringsVm::endSliderChange,
                        onMakeCircle = ringsVm::makeCircle,
                        onUseDefault = ringsVm::useDefault,
                        onRevert = ringsVm::revert,
                        onMapLeds = {
                            mapVm.goToFirstUnmapped()
                            vm.selectStep(WallSetupStep.MAP_LEDS)
                        },
                    )
                },
            )
        },
        review = {
            ReviewStep(
                state = reviewState,
                actions = remember(reviewVm, mapVm, vm) {
                    ReviewActions(
                        onTapHold = reviewVm::tapHold,
                        onTapEmpty = reviewVm::tapEmpty,
                        onLightAll = reviewVm::setLightAll,
                        onShowUnmapped = {
                            mapVm.goToFirstUnmapped()
                            vm.selectStep(WallSetupStep.MAP_LEDS)
                        },
                        onStartMove = reviewVm::startMove,
                        onDrag = reviewVm::dragBy,
                        onSaveMove = reviewVm::saveMove,
                        onCancelMove = reviewVm::cancelMove,
                        onRemoveLed = reviewVm::removeLed,
                        onRequestDelete = reviewVm::requestDelete,
                        onConfirmDelete = reviewVm::confirmDelete,
                        onDismissDelete = reviewVm::dismissDelete,
                        onExport = { exportJson.launch(LayoutFormat.JSON.fileName) },
                    )
                },
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallSetupContent(
    state: WallSetupUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSelectStep: (WallSetupStep) -> Unit,
    onMessageShown: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    alignActions: AlignActions,
    mapLeds: @Composable () -> Unit,
    rings: @Composable () -> Unit,
    review: @Composable () -> Unit,
) {
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wall Setup") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = state.step.ordinal) {
                WallSetupStep.entries.forEach {
                    Tab(selected = it == state.step, onClick = { onSelectStep(it) }, text = { Text(it.title) })
                }
            }
            when (state.step) {
                WallSetupStep.PHOTO -> PhotoStep(state.photo, state.hasPhoto, state.working, onTakePhoto, onChoosePhoto)
                WallSetupStep.ALIGN -> AlignStep(state, alignActions)
                WallSetupStep.MAP_LEDS -> mapLeds()
                WallSetupStep.RINGS -> rings()
                WallSetupStep.REVIEW -> review()
            }
        }
    }
}

/**
 * Where the system camera saves the photo (§8.6). The path is fixed, so the URI can be rebuilt
 * if the camera app outlives this screen or our process.
 */
private fun cameraCaptureUri(context: Context): Uri {
    val file = File(context.cacheDir, "camera/capture.jpg")
    file.parentFile?.mkdirs()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
