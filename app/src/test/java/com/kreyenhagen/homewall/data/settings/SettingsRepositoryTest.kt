package com.kreyenhagen.homewall.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.kreyenhagen.homewall.domain.GradeFilter
import com.kreyenhagen.homewall.domain.RouteSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(tmp.root, "settings.preferences_pb") },
    )

    @Test
    fun emptyStoreGivesTheDefaults() = runTest {
        val defaults = AppSettings(showLedPreview = true)
        val settings = SettingsRepository(newStore(), defaults).settings.first()

        assertEquals(defaults, settings)
        // §6.5
        assertEquals(RouteSort.NEWEST, settings.sort)
        assertEquals(GradeFilter(0, 17, includeProjects = true), settings.gradeFilter)
        assertNull(settings.savedDevice)
        assertEquals(50, settings.ledBrightnessPercent)
        assertEquals(3.5f, settings.ringSizeIn)
        assertEquals(true, settings.showHoldMarkers)
        assertEquals(true, settings.keepScreenOn)
        assertEquals(false, settings.simulatedLeds)
    }

    @Test
    fun everySettingRoundTrips() = runTest {
        val repo = SettingsRepository(newStore())
        repo.setSort(RouteSort.GRADE_LOW_TO_HIGH)
        repo.setGradeFilter(GradeFilter(3, 7, includeProjects = false))
        repo.setSavedDevice(SavedDevice("24:6F:28:AA:BB:CC", "HomeWall"))
        repo.setLedBrightness(80)
        repo.setRingSize(4.5f)
        repo.setShowHoldMarkers(false)
        repo.setShowLedPreview(true)
        repo.setKeepScreenOn(false)
        repo.setSimulatedLeds(true)

        assertEquals(
            AppSettings(
                sort = RouteSort.GRADE_LOW_TO_HIGH,
                gradeFilter = GradeFilter(3, 7, includeProjects = false),
                savedDevice = SavedDevice("24:6F:28:AA:BB:CC", "HomeWall"),
                ledBrightnessPercent = 80,
                ringSizeIn = 4.5f,
                showHoldMarkers = false,
                showLedPreview = true,
                keepScreenOn = false,
                simulatedLeds = true,
            ),
            repo.settings.first(),
        )
    }

    @Test
    fun forgettingTheDeviceClearsIt() = runTest {
        val repo = SettingsRepository(newStore())
        repo.setSavedDevice(SavedDevice("24:6F:28:AA:BB:CC", "HomeWall"))
        repo.setSavedDevice(null)

        assertNull(repo.settings.first().savedDevice)
    }

    @Test
    fun settersClampOutOfRangeValues() = runTest {
        val repo = SettingsRepository(newStore())
        repo.setLedBrightness(0)
        repo.setRingSize(20f)
        repo.setGradeFilter(GradeFilter(minGrade = 9, maxGrade = 4))

        val s = repo.settings.first()
        assertEquals(5, s.ledBrightnessPercent)
        assertEquals(6f, s.ringSizeIn)
        assertEquals(GradeFilter(9, 9), s.gradeFilter)
    }

    @Test
    fun badStoredValuesFallBackOrAreClamped() = runTest {
        val store = newStore()
        store.edit {
            it[SettingsKeys.SORT] = "SOMETHING_OLD"
            it[SettingsKeys.BRIGHTNESS] = 500
            it[SettingsKeys.RING_SIZE] = Float.NaN
            it[SettingsKeys.FILTER_MIN] = 12
            it[SettingsKeys.FILTER_MAX] = 30
        }

        val s = SettingsRepository(store).settings.first()
        assertEquals(RouteSort.NEWEST, s.sort)
        assertEquals(100, s.ledBrightnessPercent)
        assertEquals(3.5f, s.ringSizeIn)
        assertEquals(GradeFilter(12, 17), s.gradeFilter)
    }
}
