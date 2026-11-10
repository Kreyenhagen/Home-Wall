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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kreyenhagen.homewall.ui.appViewModel
import com.kreyenhagen.homewall.ui.components.DriveLeds
import com.kreyenhagen.homewall.ui.components.WallLeds
import com.kreyenhagen.homewall.ui.components.BackButton
import com.kreyenhagen.homewall.ui.components.StubBody
import java.io.File

@Composable
fun WallSetupScreen(onBack: () -> Unit) {
    val vm = appViewModel { WallSetupViewModel(it.layoutRepository, it.wallPhotoRepository, it.settingsRepository) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    // Map LEDs and Review light their own LEDs in M6; until then the last route stays lit (§9.2).
    DriveLeds(WallLeds.LastRoute)
    val context = LocalContext.current

    val choosePhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importPhoto(uri)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) vm.importPhoto(cameraCaptureUri(context))
    }

    WallSetupContent(
        state = state,
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
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallSetupContent(
    state: WallSetupUiState,
    onBack: () -> Unit,
    onSelectStep: (WallSetupStep) -> Unit,
    onMessageShown: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    alignActions: AlignActions,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wall Setup") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
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
                WallSetupStep.MAP_LEDS -> StubBody("Map LEDs", "Light one LED at a time and tap where it shows up. Arrives in M6.")
                WallSetupStep.REVIEW -> StubBody("Review", "Check every hold against the real wall. Arrives in M6.")
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
