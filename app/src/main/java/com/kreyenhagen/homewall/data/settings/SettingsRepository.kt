package com.kreyenhagen.homewall.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kreyenhagen.homewall.domain.GradeFilter
import com.kreyenhagen.homewall.domain.Grades
import com.kreyenhagen.homewall.domain.RouteSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class SavedDevice(val address: String, val name: String)

/** Everything in §6.5. Defaults match the table there. */
data class AppSettings(
    val sort: RouteSort = RouteSort.NEWEST,
    val gradeFilter: GradeFilter = GradeFilter(),
    val savedDevice: SavedDevice? = null,
    val ledBrightnessPercent: Int = 50,
    val ringSizeIn: Float = 3.5f,
    /** On in debug builds; [com.kreyenhagen.homewall.AppContainer] passes that in. */
    val showLedPreview: Boolean = false,
    val keepScreenOn: Boolean = true,
    val simulatedLeds: Boolean = false,
) {
    companion object {
        val BRIGHTNESS_RANGE = 5..100
        const val BRIGHTNESS_STEP = 5
        val RING_SIZE_RANGE = 1f..6f
        const val RING_SIZE_STEP = 0.5f
    }
}

/** The single owner of app settings (§4), backed by DataStore Preferences. */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val defaults: AppSettings = AppSettings(),
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toAppSettings(defaults) }
        .distinctUntilChanged()

    suspend fun setSort(sort: RouteSort) = edit { it[SettingsKeys.SORT] = sort.name }

    suspend fun setGradeFilter(filter: GradeFilter) = edit {
        val min = filter.minGrade.coerceIn(Grades.MIN, Grades.MAX)
        it[SettingsKeys.FILTER_MIN] = min
        it[SettingsKeys.FILTER_MAX] = filter.maxGrade.coerceIn(min, Grades.MAX)
        it[SettingsKeys.FILTER_PROJECTS] = filter.includeProjects
    }

    suspend fun setSavedDevice(device: SavedDevice?) = edit {
        if (device == null) {
            it.remove(SettingsKeys.DEVICE_ADDRESS)
            it.remove(SettingsKeys.DEVICE_NAME)
        } else {
            it[SettingsKeys.DEVICE_ADDRESS] = device.address
            it[SettingsKeys.DEVICE_NAME] = device.name
        }
    }

    suspend fun setLedBrightness(percent: Int) = edit {
        it[SettingsKeys.BRIGHTNESS] = percent.coerceIn(AppSettings.BRIGHTNESS_RANGE)
    }

    suspend fun setRingSize(inches: Float) = edit {
        it[SettingsKeys.RING_SIZE] = inches.coerceIn(AppSettings.RING_SIZE_RANGE)
    }


    suspend fun setShowLedPreview(show: Boolean) = edit { it[SettingsKeys.SHOW_LED_PREVIEW] = show }

    suspend fun setKeepScreenOn(on: Boolean) = edit { it[SettingsKeys.KEEP_SCREEN_ON] = on }

    suspend fun setSimulatedLeds(on: Boolean) = edit { it[SettingsKeys.SIMULATED] = on }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: IOException) {
            // A failed settings write isn't worth crashing for; the old value stays.
            Log.w("HomeWall/Settings", "Couldn't save settings", e)
        }
    }
}

internal object SettingsKeys {
    val SORT = stringPreferencesKey("list_sort")
    val FILTER_MIN = intPreferencesKey("filter_min_grade")
    val FILTER_MAX = intPreferencesKey("filter_max_grade")
    val FILTER_PROJECTS = booleanPreferencesKey("filter_include_projects")
    val DEVICE_ADDRESS = stringPreferencesKey("ble_device_address")
    val DEVICE_NAME = stringPreferencesKey("ble_device_name")
    val BRIGHTNESS = intPreferencesKey("led_brightness_percent")
    val RING_SIZE = floatPreferencesKey("ring_size_in")
    val SHOW_LED_PREVIEW = booleanPreferencesKey("show_led_preview")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val SIMULATED = booleanPreferencesKey("simulated_led_controller")
}

/** Reads stored values, falling back to [d] and clamping anything out of range (e.g. a hand-edited file). */
internal fun Preferences.toAppSettings(d: AppSettings): AppSettings {
    val minGrade = (this[SettingsKeys.FILTER_MIN] ?: d.gradeFilter.minGrade).coerceIn(Grades.MIN, Grades.MAX)
    val maxGrade = (this[SettingsKeys.FILTER_MAX] ?: d.gradeFilter.maxGrade).coerceIn(minGrade, Grades.MAX)
    val address = this[SettingsKeys.DEVICE_ADDRESS]
    val ringSize = this[SettingsKeys.RING_SIZE]?.takeUnless { it.isNaN() } ?: d.ringSizeIn
    return AppSettings(
        sort = this[SettingsKeys.SORT]?.let { name -> RouteSort.entries.firstOrNull { it.name == name } } ?: d.sort,
        gradeFilter = GradeFilter(
            minGrade = minGrade,
            maxGrade = maxGrade,
            includeProjects = this[SettingsKeys.FILTER_PROJECTS] ?: d.gradeFilter.includeProjects,
        ),
        savedDevice = if (address != null) SavedDevice(address, this[SettingsKeys.DEVICE_NAME].orEmpty()) else d.savedDevice,
        ledBrightnessPercent = (this[SettingsKeys.BRIGHTNESS] ?: d.ledBrightnessPercent).coerceIn(AppSettings.BRIGHTNESS_RANGE),
        ringSizeIn = ringSize.coerceIn(AppSettings.RING_SIZE_RANGE),
        showLedPreview = this[SettingsKeys.SHOW_LED_PREVIEW] ?: d.showLedPreview,
        keepScreenOn = this[SettingsKeys.KEEP_SCREEN_ON] ?: d.keepScreenOn,
        simulatedLeds = this[SettingsKeys.SIMULATED] ?: d.simulatedLeds,
    )
}
