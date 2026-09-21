package dev.omniwallet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import dev.omniwallet.app.ui.OmniWalletApp
import dev.omniwallet.app.ui.settings.AppSettings
import dev.omniwallet.app.ui.settings.SettingsStore
import dev.omniwallet.app.ui.theme.OmniWalletTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsStore.settings.collectAsState(initial = AppSettings())
            OmniWalletTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                OmniWalletApp()
            }
        }
    }
}
