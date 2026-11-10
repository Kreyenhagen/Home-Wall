package com.kreyenhagen.homewall.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.kreyenhagen.homewall.MainDispatcherRule
import com.kreyenhagen.homewall.data.backup.BackupArchive
import com.kreyenhagen.homewall.data.backup.BackupContents
import com.kreyenhagen.homewall.data.backup.BackupManifest
import com.kreyenhagen.homewall.data.layout.layoutOf
import com.kreyenhagen.homewall.data.settings.SettingsRepository
import com.kreyenhagen.homewall.led.FakeLedController
import com.kreyenhagen.homewall.util.LogBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** Settings that never change; these tests are about the backup flow. */
    private val store = object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val backup = BackupContents(
        manifest = BackupManifest(appVersion = "1.0", createdAt = "2026-11-10T14:30:00+01:00"),
        routes = emptyList(),
        layout = layoutOf(),
        photo = null,
        alignment = null,
    )

    private val restored = mutableListOf<BackupContents>()
    private var restoreProblems = emptyList<String>()
    private var lightsOff = 0

    private fun TestScope.settings(): SettingsViewModel {
        val log = LogBuffer()
        val vm = SettingsViewModel(
            repo = SettingsRepository(store),
            controller = FakeLedController(log, flowOf(emptyMap())),
            logBuffer = log,
            restoreBackup = { restored += it; restoreProblems },
            lightsOff = { lightsOff++ },
        )
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    private val SettingsViewModel.state get() = uiState.value

    @Test
    fun aGoodBackupAsksFirstThenReplacesEverythingAndTurnsTheWallOff() = runTest {
        val vm = settings()
        vm.onBackupRead(BackupArchive.ReadResult.Ok(backup))
        assertSame(backup, vm.state?.pendingBackup)
        assertEquals(emptyList<BackupContents>(), restored) // nothing replaced before I confirm

        vm.confirmBackup()
        assertEquals(listOf(backup), restored)
        assertNull(vm.state?.pendingBackup)
        assertEquals(1, lightsOff) // the remembered route may not exist any more
        assertEquals("Backup restored", vm.state?.message?.text)
    }

    @Test
    fun cancellingReplacesNothing() = runTest {
        val vm = settings()
        vm.onBackupRead(BackupArchive.ReadResult.Ok(backup))
        vm.cancelBackup()
        vm.confirmBackup()
        assertNull(vm.state?.pendingBackup)
        assertEquals(emptyList<BackupContents>(), restored)
        assertEquals(0, lightsOff)
    }

    @Test
    fun aBadBackupListsItsProblems() = runTest {
        val vm = settings()
        vm.onBackupRead(BackupArchive.ReadResult.Failed(listOf("The backup has no routes.json.")))
        assertEquals(ImportProblems("This backup can't be restored", listOf("The backup has no routes.json.")), vm.state?.importProblems)
        assertNull(vm.state?.pendingBackup)
        vm.dismissImportProblems()
        assertNull(vm.state?.importProblems)
    }

    @Test
    fun aRestoreThatFailsPartWaySaysWhatHappened() = runTest {
        restoreProblems = listOf("Routes and layout were restored, but the photo couldn't be straightened: boom")
        val vm = settings()
        vm.onBackupRead(BackupArchive.ReadResult.Ok(backup))
        vm.confirmBackup()
        assertEquals(ImportProblems("The backup was only partly restored", restoreProblems), vm.state?.importProblems)
        assertEquals(1, lightsOff)
    }
}
