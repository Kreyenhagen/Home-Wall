package com.kreyenhagen.homewall

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.photo.WallPhotoRepository
import com.kreyenhagen.homewall.data.route.AppDatabase
import com.kreyenhagen.homewall.data.route.RouteRepository
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.data.settings.SettingsRepository
import com.kreyenhagen.homewall.data.settings.settingsDataStore
import com.kreyenhagen.homewall.led.FakeLedController
import com.kreyenhagen.homewall.led.LedController
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.led.Rgb
import com.kreyenhagen.homewall.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual DI (§3): every app-scoped object is created here, once. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process. Owns the LED connection, so it survives screen changes and rotation. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val logBuffer = LogBuffer()

    val layoutRepository = LayoutRepository(
        file = File(appContext.filesDir, "wall_layout.json"),
        readAsset = { appContext.readAssetOrNull("wall_layout.json") },
    )

    val wallPhotoRepository = WallPhotoRepository(appContext.filesDir, appContext.contentResolver)

    private val database = AppDatabase.create(appContext)

    val routeRepository = RouteRepository(database.routeDao())

    val settingsRepository = SettingsRepository(
        dataStore = appContext.settingsDataStore,
        defaults = AppSettings(showLedPreview = appContext.isDebuggable()),
    )

    /** LED palette at the current brightness (§9.3). Controllers send it on every (re)connect. */
    val palette: Flow<Map<Int, Rgb>> = settingsRepository.settings
        .map { Palette.scaled(it.ledBrightnessPercent) }
        .distinctUntilChanged()

    val fakeLedController = FakeLedController(logBuffer, palette)

    /**
     * The controller the rest of the app talks to. Only the simulated one exists so far;
     * M5 adds BleLedController and switches between the two.
     */
    val ledController: LedController = fakeLedController

    init {
        appScope.launch {
            layoutRepository.load()
            wallPhotoRepository.load(layoutRepository.state.value.layoutOrNull?.wall)
        }
        appScope.launch {
            settingsRepository.settings
                .map { it.simulatedLeds }
                .distinctUntilChanged()
                .collect { simulated ->
                    if (simulated) fakeLedController.connect() else fakeLedController.disconnect()
                }
        }
    }
}

private fun Context.isDebuggable(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private fun Context.readAssetOrNull(name: String): String? = try {
    assets.open(name).bufferedReader().use { it.readText() }
} catch (e: FileNotFoundException) {
    null
}
