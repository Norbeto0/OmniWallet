package dev.omniwallet.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.omniwallet.app.ui.wallet.WalletScreen

object Routes {
    const val WALLET = "wallet"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun OmniWalletApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.WALLET) {
        composable(Routes.WALLET) {
            WalletScreen(onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) })
        }
    }
}
