package com.airclip.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.airclip.AirClipApp
import com.airclip.R
import com.airclip.ui.screens.DevicesScreen
import com.airclip.ui.screens.HistoryScreen
import com.airclip.ui.screens.HomeScreen
import com.airclip.ui.screens.LogScreen
import com.airclip.ui.screens.PairScreen
import com.airclip.ui.screens.SettingsScreen
import com.airclip.ui.theme.AirClipTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The only user-facing activity. Everything it shows is a view onto `AirClipRuntime`, so the UI can
 * be closed at any point without affecting the sync service.
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val runtime by lazy { AirClipApp.runtime(this) }

    /**
     * `airclip://pair` links arrive both before composition (cold start from a QR scanner) and long
     * after it (`onNewIntent`), so they are replayed into the UI rather than read from `intent`.
     * Re-applying the same key on a configuration change is harmless: the vault stores it again and
     * the snackbar simply repeats the fingerprint.
     */
    private val pairingLinks = MutableSharedFlow<String>(replay = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirClipTheme {
                AirClipRoot(vm = vm, pairingLinks = pairingLinks)
            }
        }
        consume(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    /**
     * A resumed activity has window focus, which is the platform's own condition for allowing a
     * clipboard read — so 立即发送剪贴板 works here even before any background plan is set up.
     * Balanced by [onPause]; the counter in the runtime tolerates overlapping windows.
     */
    override fun onResume() {
        super.onResume()
        runtime.openReadWindow()
        // Permissions and system switches can all have changed while the user was in Settings.
        vm.refresh()
    }

    override fun onPause() {
        runtime.closeReadWindow()
        super.onPause()
    }

    private fun consume(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.toString()?.let(pairingLinks::tryEmit)
    }
}

/** The four bottom-bar destinations. 配对 is a detail screen, so it is deliberately not one. */
private enum class Destination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home),
    DEVICES("devices", R.string.nav_devices, Icons.Filled.Devices),
    HISTORY("history", R.string.nav_history, Icons.Filled.History),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}

private const val ROUTE_PAIR = "pair"
private const val ROUTE_LOG = "log"

/** Detail routes: reached from another screen, so they get a back arrow and no bottom bar. */
private val DETAIL_ROUTES = setOf(ROUTE_PAIR, ROUTE_LOG)

@StringRes
private fun titleFor(route: String?): Int = when (route) {
    Destination.DEVICES.route -> R.string.nav_devices
    Destination.HISTORY.route -> R.string.nav_history
    Destination.SETTINGS.route -> R.string.nav_settings
    ROUTE_PAIR -> R.string.pair_title
    ROUTE_LOG -> R.string.log_title
    else -> R.string.app_name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirClipRoot(vm: MainViewModel, pairingLinks: SharedFlow<String>) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Destination.HOME.route

    // One snackbar host for the whole app: a send started on any screen is reported the same way.
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        pairingLinks.collect { link ->
            vm.applyPairingText(link)
            navController.navigate(ROUTE_PAIR) { launchSingleTop = true }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleFor(route))) },
                navigationIcon = {
                    if (route in DETAIL_ROUTES) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_close))
                        }
                    }
                },
                actions = {
                    when (route) {
                        Destination.DEVICES.route -> IconButton(onClick = vm::rescan) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.devices_refresh))
                        }

                        Destination.HISTORY.route -> IconButton(onClick = vm::clearHistory) {
                            Icon(Icons.Filled.DeleteSweep, stringResource(R.string.history_clear))
                        }

                        else -> Unit
                    }
                },
            )
        },
        bottomBar = {
            if (route !in DETAIL_ROUTES) {
                BottomBar(current = route) { target ->
                    navController.navigate(target) {
                        // The four tabs are siblings, not a stack: keep one entry and restore state.
                        popUpTo(Destination.HOME.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.HOME.route) {
                HomeScreen(vm = vm, onPair = { navController.navigate(ROUTE_PAIR) })
            }
            composable(Destination.DEVICES.route) { DevicesScreen(vm = vm) }
            composable(Destination.HISTORY.route) { HistoryScreen(vm = vm) }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(
                    vm = vm,
                    onPair = { navController.navigate(ROUTE_PAIR) },
                    onLog = { navController.navigate(ROUTE_LOG) },
                )
            }
            composable(ROUTE_PAIR) { PairScreen(vm = vm) }
            composable(ROUTE_LOG) { LogScreen(vm = vm) }
        }
    }
}

@Composable
private fun BottomBar(current: String, onNavigate: (String) -> Unit) {
    NavigationBar {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = current == destination.route,
                onClick = { if (current != destination.route) onNavigate(destination.route) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}
