package com.kreyenhagen.homewall.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.backup.BackupArchive
import com.kreyenhagen.homewall.data.backup.BackupContents
import com.kreyenhagen.homewall.data.layout.LayoutFormat
import com.kreyenhagen.homewall.data.layout.LayoutImport
import com.kreyenhagen.homewall.data.layout.LayoutTransfer
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.data.settings.SettingsRepository
import com.kreyenhagen.homewall.led.LedController
import com.kreyenhagen.homewall.ui.components.UserMessage
import com.kreyenhagen.homewall.ui.components.label
import com.kreyenhagen.homewall.util.LogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Why an import couldn't be used: every problem found. */
data class ImportProblems(val title: String, val problems: List<String>)

data class SettingsUiState(
    val settings: AppSettings,
    val connection: String,
    /** Newest first. */
    val log: List<String>,
    /** A layout read from a file, waiting for me to confirm the import (§12.3). */
    val pendingImport: LayoutImport? = null,
    /** A backup read from a file, waiting for me to confirm the restore (§13). */
    val pendingBackup: BackupContents? = null,
    val importProblems: ImportProblems? = null,
    val busy: Boolean = false,
    val message: UserMessage? = null,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    controller: LedController,
    logBuffer: LogBuffer,
    /** Forgetting the board also disconnects from it (§8.1). */
    private val disconnect: () -> Unit = {},
    /** Layout import and export (§8.5, §12.3); LayoutTransfer in the app. */
    private val readLayout: suspend (Uri) -> LayoutTransfer.ReadResult = { LayoutTransfer.ReadResult.Failed(listOf("Not available")) },
    private val applyLayout: suspend (LayoutImport) -> List<String> = { emptyList() },
    private val writeLayout: suspend (Uri, LayoutFormat) -> String? = { _, _ -> null },
    /** Backup export and import (§13); BackupManager in the app. */
    private val readBackup: suspend (Uri) -> BackupArchive.ReadResult = { BackupArchive.ReadResult.Failed(listOf("Not available")) },
    private val restoreBackup: suspend (BackupContents) -> List<String> = { emptyList() },
    private val writeBackup: suspend (Uri) -> String? = { null },
    /** `homewall-backup-YYYYMMDD-HHmm.zip` for now (§13). */
    val backupFileName: () -> String = { "homewall-backup.zip" },
    /** After a restore the remembered route may be gone, so the wall goes dark. */
    private val lightsOff: () -> Unit = {},
) : ViewModel() {

    private data class Local(
        val pendingImport: LayoutImport? = null,
        val pendingBackup: BackupContents? = null,
        val importProblems: ImportProblems? = null,
        val busy: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())

    /** Null until settings have loaded, so controls don't flash their defaults. */
    val uiState: StateFlow<SettingsUiState?> =
        combine(repo.settings, controller.state, logBuffer.lines, local) { settings, connection, lines, l ->
            SettingsUiState(
                settings = settings,
                connection = connection.label,
                log = lines.asReversed().map { it.format() },
                pendingImport = l.pendingImport,
                pendingBackup = l.pendingBackup,
                importProblems = l.importProblems,
                busy = l.busy,
                message = l.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- Layout import and export ----

    /** Reads and validates the file; nothing changes until [confirmImport]. */
    fun importLayout(uri: Uri) = withBusy {
        when (val result = readLayout(uri)) {
            is LayoutTransfer.ReadResult.Ok -> local.update { it.copy(pendingImport = result.import) }
            is LayoutTransfer.ReadResult.Failed -> showProblems(LAYOUT_PROBLEMS, result.problems)
        }
    }

    fun confirmImport() {
        val import = local.value.pendingImport ?: return
        local.update { it.copy(pendingImport = null) }
        withBusy {
            val problems = applyLayout(import)
            if (problems.isEmpty()) show("Layout imported") else showProblems(LAYOUT_PROBLEMS, problems)
        }
    }

    fun cancelImport() = local.update { it.copy(pendingImport = null) }

    fun dismissImportProblems() = local.update { it.copy(importProblems = null) }

    fun exportLayout(uri: Uri, format: LayoutFormat) = withBusy {
        show(writeLayout(uri, format) ?: "Layout exported")
    }

    // ---- Backup (§13) ----

    fun exportBackup(uri: Uri) = withBusy {
        show(writeBackup(uri) ?: "Backup saved")
    }

    /** Reads and checks the backup; nothing changes until [confirmBackup]. */
    fun importBackup(uri: Uri) = withBusy { onBackupRead(readBackup(uri)) }

    internal fun onBackupRead(result: BackupArchive.ReadResult) = when (result) {
        is BackupArchive.ReadResult.Ok -> local.update { it.copy(pendingBackup = result.contents) }
        is BackupArchive.ReadResult.Failed -> showProblems(BACKUP_PROBLEMS, result.problems)
    }

    fun confirmBackup() {
        val contents = local.value.pendingBackup ?: return
        local.update { it.copy(pendingBackup = null) }
        withBusy {
            val problems = restoreBackup(contents)
            lightsOff()
            if (problems.isEmpty()) show("Backup restored") else showProblems("The backup was only partly restored", problems)
        }
    }

    fun cancelBackup() = local.update { it.copy(pendingBackup = null) }

    fun messageShown(id: Long) = local.update { if (it.message?.id == id) it.copy(message = null) else it }

    private fun show(text: String) = local.update { it.copy(message = UserMessage(text)) }

    private fun showProblems(title: String, problems: List<String>) =
        local.update { it.copy(importProblems = ImportProblems(title, problems)) }

    private fun withBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    // ---- Settings ----

    fun setShowHoldMarkers(show: Boolean) = launch { repo.setShowHoldMarkers(show) }
    fun setShowLedPreview(show: Boolean) = launch { repo.setShowLedPreview(show) }
    fun setKeepScreenOn(on: Boolean) = launch { repo.setKeepScreenOn(on) }
    fun setSimulatedLeds(on: Boolean) = launch { repo.setSimulatedLeds(on) }
    fun forgetDevice() {
        disconnect()
        launch { repo.setSavedDevice(null) }
    }

    /** LedSync resends the palette as soon as the new value is stored (§9.3). */
    fun setBrightness(percent: Float) = launch { repo.setLedBrightness(percent.roundToInt()) }

    fun setRingSize(inches: Float) = launch {
        val step = AppSettings.RING_SIZE_STEP
        repo.setRingSize((inches / step).roundToInt() * step)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

private const val LAYOUT_PROBLEMS = "This layout can't be imported"
private const val BACKUP_PROBLEMS = "This backup can't be restored"

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun LogBuffer.Line.format(): String {
    val time = TIME_FORMAT.format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))
    return "$time  $tag  $message"
}
