package com.kreyenhagen.homewall

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.kreyenhagen.homewall.ble.BleLedController
import com.kreyenhagen.homewall.ble.BlePermissions
import com.kreyenhagen.homewall.data.backup.BackupManager
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutTransfer
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.photo.WallPhotoRepository
import com.kreyenhagen.homewall.data.route.AppDatabase
import com.kreyenhagen.homewall.data.route.RouteRepository
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.data.settings.SettingsRepository
import com.kreyenhagen.homewall.data.settings.settingsDataStore
import com.kreyenhagen.homewall.led.ActiveLedController
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.led.FakeLedController
import com.kreyenhagen.homewall.led.LedController
import com.kreyenhagen.homewall.led.LedSync
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.led.Rgb
import com.kreyenhagen.homewall.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(ForegroundTracker(container::setForeground))
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

    /** Layout import and export through the system file pickers (§12.3). */
    val layoutTransfer = LayoutTransfer(appContext.contentResolver, layoutRepository, wallPhotoRepository)

    private val database = AppDatabase.create(appContext)

    val routeRepository = RouteRepository(database.routeDao())

    /** Settings → Export / Import backup (§13). */
    val backupManager = BackupManager(
        resolver = appContext.contentResolver,
        routes = routeRepository,
        layout = layoutRepository,
        photo = wallPhotoRepository,
        appVersion = appContext.versionName(),
    )

    /** The route Route Display showed last, so Route List can scroll back to it (§8.1). In memory only. */
    val lastViewedRouteId = MutableStateFlow<Long?>(null)

    val settingsRepository = SettingsRepository(
        dataStore = appContext.settingsDataStore,
        defaults = AppSettings(showLedPreview = appContext.isDebuggable()),
    )

    private val _foreground = MutableStateFlow(false)

    /** True while any activity is started. Bluetooth only auto-reconnects in the foreground (§10.4). */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun setForeground(value: Boolean) {
        _foreground.value = value
    }

    /** LED palette at the current brightness (§9.3). Controllers send it on every (re)connect. */
    val palette: Flow<Map<Int, Rgb>> = settingsRepository.settings
        .map { Palette.scaled(it.ledBrightnessPercent) }
        .distinctUntilChanged()

    val fakeLedController = FakeLedController(logBuffer, palette)

    val bleLedController = BleLedController(appContext, logBuffer, palette, foreground, appScope)

    private val simulated: StateFlow<Boolean> = settingsRepository.settings
        .map { it.simulatedLeds }
        .distinctUntilChanged()
        .stateIn(appScope, SharingStarted.Eagerly, false)

    /** The controller the rest of the app sees: simulated or Bluetooth, per Settings (§10.5). */
    val ledController: LedController = ActiveLedController(fakeLedController, bleLedController, simulated, appScope)

    /** The only thing that talks to the controller (§9.1). */
    val ledSync = LedSync(ledController, palette, appScope, logBuffer)

    init {
        appScope.launch {
            layoutRepository.load()
            wallPhotoRepository.load(layoutRepository.state.value.layoutOrNull?.wall)
        }
        appScope.launch {
            // Straight from settings (not the StateFlow above), so its placeholder `false` never
            // starts a Bluetooth connection before the stored value has loaded.
            settingsRepository.settings
                .map { it.simulatedLeds }
                .distinctUntilChanged()
                .collect { sim ->
                    if (sim) {
                        bleLedController.stopScan()
                        bleLedController.disconnect()
                        fakeLedController.connect()
                    } else {
                        fakeLedController.disconnect()
                        connectSavedDevice()
                    }
                }
        }
    }

    /**
     * At launch (and when Simulated is turned off), connect to the saved board. This also covers
     * §10.4's "reconnect when the app comes back" after Android ended the process in the
     * background. Drops while the app is running are handled by BleLedController's retry loop.
     */
    private suspend fun connectSavedDevice() {
        val settings = settingsRepository.settings.first()
        if (settings.simulatedLeds) return
        val device = settings.savedDevice ?: return
        if (bleLedController.state.value != ConnectionState.Disconnected) return
        if (!BlePermissions.granted(appContext) || !BlePermissions.bluetoothOn(appContext)) return
        bleLedController.connect(device.address, device.name)
    }
}

/** Counts started activities to tell when the app is in the foreground. */
private class ForegroundTracker(private val onChange: (Boolean) -> Unit) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) onChange(true)
    }

    override fun onActivityStopped(activity: Activity) {
        if (--started == 0) onChange(false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

private fun Context.isDebuggable(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private fun Context.readAssetOrNull(name: String): String? = try {
    assets.open(name).bufferedReader().use { it.readText() }
} catch (e: FileNotFoundException) {
    null
}

private fun Context.versionName(): String =
    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
