package dev.omniwallet.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.omniwallet.app.ui.device.DeviceScreen
import dev.omniwallet.app.ui.diagnostics.DiagnosticsScreen
import dev.omniwallet.app.ui.nowemulating.NowEmulatingBar
import dev.omniwallet.app.ui.settings.SettingsScreen
import dev.omniwallet.app.ui.wallet.WalletScreen

object Routes {
    const val WALLET = "wallet"
    const val DEVICE = "device"
    const val SETTINGS = "settings"
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
 * The app shell.
 *
 * The now-emulating bar sits directly above the navigation bar and is rendered
 * by the shell rather than by any screen, which is what lets it persist while
 * you move between tabs. It is driven by a singleton, so it keeps ticking
 * whatever is on screen.
 */
@Composable
fun OmniWalletApp(shellViewModel: ShellViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val nowEmulating by shellViewModel.nowEmulating.collectAsStateWithLifecycle()
    val showBars = currentRoute?.route != Routes.DIAGNOSTICS

    Scaffold(
        bottomBar = {
            if (showBars) {
                Column {
                    NowEmulatingBar(
                        nowEmulating = nowEmulating,
                        onStop = shellViewModel::stop,
                        onExpand = { navController.navigate(Routes.WALLET) { launchSingleTop = true } },
                    )
                    NavigationBar {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
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
                    onOpenDevice = { navController.navigate(Routes.DEVICE) },
                    contentPadding = padding,
                )
            }
            composable(Routes.DEVICE) {
                DeviceScreen(
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
}
