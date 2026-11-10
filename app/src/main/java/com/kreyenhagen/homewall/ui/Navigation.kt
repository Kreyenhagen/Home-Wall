package com.kreyenhagen.homewall.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.kreyenhagen.homewall.AppContainer
import com.kreyenhagen.homewall.ui.editor.RouteEditorScreen
import com.kreyenhagen.homewall.ui.routedisplay.RouteDisplayScreen
import com.kreyenhagen.homewall.ui.routelist.RouteListScreen
import com.kreyenhagen.homewall.ui.settings.SettingsScreen
import com.kreyenhagen.homewall.ui.wallsetup.WallSetupScreen
import kotlinx.serialization.Serializable

// ---- Destinations (§8) ----

@Serializable
data object RouteList

@Serializable
data class RouteDisplay(val routeId: Long)

@Serializable
data class RouteEditor(val routeId: Long = NEW_ROUTE) {
    val isNew: Boolean get() = routeId == NEW_ROUTE

    companion object {
        const val NEW_ROUTE = -1L
    }
}

@Serializable
data object Settings

@Serializable
data object WallSetup

// ---- DI for composables ----

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** A ViewModel scoped to the current back-stack entry, built from the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(noinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel { create(container) }
}

// ---- Nav graph ----

@Composable
fun HomeWallNavHost() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = RouteList) {
        composable<RouteList> { entry ->
            RouteListScreen(
                onCreateRoute = { entry.ifResumed { nav.navigate(RouteEditor()) } },
                onOpenSettings = { entry.ifResumed { nav.navigate(Settings) } },
            )
        }

        composable<RouteDisplay> { entry ->
            val routeId = entry.toRoute<RouteDisplay>().routeId
            RouteDisplayScreen(
                routeId = routeId,
                onBack = { entry.ifResumed { nav.popBackStack() } },
                onEdit = { entry.ifResumed { nav.navigate(RouteEditor(routeId)) } },
            )
        }

        composable<RouteEditor> { entry ->
            val dest = entry.toRoute<RouteEditor>()
            RouteEditorScreen(
                isNew = dest.isNew,
                onBack = { entry.ifResumed { nav.popBackStack() } },
                onSaved = { savedId ->
                    entry.ifResumed {
                        if (dest.isNew) {
                            // Show the new route and drop the editor, so Back goes to the list (§8.3).
                            nav.navigate(RouteDisplay(savedId)) {
                                popUpTo<RouteEditor> { inclusive = true }
                            }
                        } else {
                            // Edit is opened from Route Display, so this returns to it (§8.4).
                            nav.popBackStack()
                        }
                    }
                },
                onDeleted = { entry.ifResumed { nav.popBackStack<RouteList>(inclusive = false) } },
            )
        }

        composable<Settings> { entry ->
            SettingsScreen(
                onBack = { entry.ifResumed { nav.popBackStack() } },
                onOpenWallSetup = { entry.ifResumed { nav.navigate(WallSetup) } },
            )
        }

        composable<WallSetup> { entry ->
            WallSetupScreen(onBack = { entry.ifResumed { nav.popBackStack() } })
        }
    }
}

/**
 * Runs [block] only while this entry is the resumed screen. Drops the second tap of a fast
 * double tap (e.g. Back twice during a transition), which would otherwise pop the start screen.
 */
private inline fun NavBackStackEntry.ifResumed(block: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) block()
}
