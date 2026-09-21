package dev.omniwallet.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.omniwallet.app.ui.device.DeviceScreen
import dev.omniwallet.app.ui.diagnostics.DiagnosticsScreen
import dev.omniwallet.app.ui.nowemulating.NowEmulatingBar
import dev.omniwallet.app.ui.nowemulating.NowEmulatingSheet
import dev.omniwallet.app.ui.settings.SettingsScreen
import dev.omniwallet.app.ui.wallet.WalletScreen

object Routes {
    /** Top-level destinations. Reach these only via [NavController.navigateToTab]. */
    const val WALLET = "wallet"
    const val DEVICE = "device"
    const val SETTINGS = "settings"

    /** A detail screen, pushed onto whichever tab opened it. */
    const val DIAGNOSTICS = "diagnostics"
}

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    Destination(Routes.WALLET, "Wallet", Icons.Outlined.CreditCard),
    Destination(Routes.DEVICE, "Device", Icons.Outlined.Memory),
    Destination(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

/**
 * Switch to a top-level destination.
 *
 * **The only way to reach a tab.** Tabs are siblings, and this idiom -- pop
 * back to the start destination saving state, then land on the target reusing
 * any existing instance -- keeps the back stack flat with exactly one tab entry.
 *
 * Reaching a tab with a plain `navigate` instead pushes it as a *child* of the
 * current tab, and the two models then fight: the pop and the `launchSingleTop`
 * reuse cancel out, the current entry never changes identity, nothing
 * recomposes, and the tab bar appears dead. That is precisely the bug this
 * function exists to make unrepeatable, so route every top-level target through
 * it and keep plain `navigate` for genuine detail screens like Diagnostics.
 */
private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The app shell.
 *
 * The now-emulating bar is rendered here rather than by any screen, which is
 * what lets it persist while you move between tabs. It owns its own detail
 * sheet rather than navigating, so tapping it from any tab leaves you exactly
 * where you were.
 */
@Composable
fun OmniWalletApp(shellViewModel: ShellViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val nowEmulating by shellViewModel.nowEmulating.collectAsStateWithLifecycle()
    var showNowEmulatingSheet by remember { mutableStateOf(false) }

    // Emulation can end while the sheet is open -- stopped from the bar, or the
    // link dropping. Clearing the flag here stops a stale "open" surviving to
    // ambush the next card the user emulates.
    LaunchedEffect(nowEmulating) {
        if (nowEmulating == null) showNowEmulatingSheet = false
    }

    val showBars = currentRoute?.route != Routes.DIAGNOSTICS

    Scaffold(
        bottomBar = {
            if (showBars) {
                Column {
                    NowEmulatingBar(
                        nowEmulating = nowEmulating,
                        onStop = shellViewModel::stop,
                        onExpand = { showNowEmulatingSheet = true },
                    )
                    NavigationBar {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute?.hierarchy
                                    ?.any { it.route == destination.route } == true,
                                onClick = { navController.navigateToTab(destination.route) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.WALLET,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.WALLET) {
                WalletScreen(
                    // A tab, not a push -- see navigateToTab.
                    onOpenDevice = { navController.navigateToTab(Routes.DEVICE) },
                    contentPadding = padding,
                )
            }
            composable(Routes.DEVICE) {
                DeviceScreen(
                    // Genuinely a detail screen, so this one really is a push.
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    contentPadding = padding,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(contentPadding = padding)
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    val session = nowEmulating
    if (showNowEmulatingSheet && session != null) {
        NowEmulatingSheet(
            session = session,
            onStop = {
                shellViewModel.stop()
                showNowEmulatingSheet = false
            },
            onDismiss = { showNowEmulatingSheet = false },
        )
    }
}
