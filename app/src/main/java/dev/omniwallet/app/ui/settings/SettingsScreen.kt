package dev.omniwallet.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.LocalActivity
import androidx.fragment.app.FragmentActivity
import dev.omniwallet.app.ui.lock.BiometricAuthenticator
import dev.omniwallet.app.ui.theme.ThemeMode
import dev.omniwallet.app.ui.theme.dynamicColorAvailable

private val LOCK_DELAYS = listOf(
    "Instantly" to 0L,
    "30s" to 30_000L,
    "2 min" to 120_000L,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastQuickAction by viewModel.lastQuickAction.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val biometricAvailable = remember(activity) {
        activity?.let { BiometricAuthenticator.isAvailable(it) } ?: false
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection("Appearance") {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        ) {
                            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }

                if (dynamicColorAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use wallpaper colours", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Match your phone's theme instead of the Flipper orange.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = viewModel::setDynamicColor,
                        )
                    }
                }
            }

            SettingsSection("Device") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Reconnect automatically", style = MaterialTheme.typography.titleSmall)
                        Text(
                            settings.lastDeviceName?.let { "Reconnects to $it when it is in range." }
                                ?: "Reconnects to the last device you used when it is in range.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autoConnect,
                        onCheckedChange = viewModel::setAutoConnect,
                    )
                }
            }

            SettingsSection("Nearby") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Suggest cards by place", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Cards you tag with a place are listed first when you are there. " +
                                "Your position is read only while the app is open, never in the " +
                                "background, and never leaves this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.nearbyRanking,
                        onCheckedChange = viewModel::setNearbyRanking,
                    )
                }
            }

            SettingsSection("Quick access") {
                Text(
                    "The home-screen widget and the Quick Settings tile work as soon as you " +
                        "add them. Both refuse and open the app instead while the app lock is " +
                        "on, because neither can ask for your fingerprint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow other apps to trigger", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Lets Tasker, an NFC tag or a shortcut ask OmniWallet to emulate a " +
                                "card. Anything on this phone that can send a broadcast can use " +
                                "it, so it stays off unless you need it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.automationEnabled,
                        onCheckedChange = viewModel::setAutomationEnabled,
                    )
                }

                if (settings.automationEnabled) {
                    Text(
                        "Send dev.omniwallet.action.EMULATE to " +
                            "dev.omniwallet.app/.quick.AutomationReceiver with a string extra " +
                            "\"name\" matching the card, or " +
                            "dev.omniwallet.action.STOP to end it.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // The audit line. Present whether or not automation is on,
                // because the widget and tile report through it too, and a
                // trigger firing when it should not is worth being able to see.
                lastQuickAction?.let { outcome ->
                    Text(
                        "Last quick action: $outcome",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            SettingsSection("Security") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock the app", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (biometricAvailable) {
                                "Require your fingerprint, face or screen lock to open OmniWallet."
                            } else {
                                "Set a screen lock on this phone to use this."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.appLockEnabled,
                        enabled = biometricAvailable,
                        onCheckedChange = viewModel::setAppLockEnabled,
                    )
                }

                if (settings.appLockEnabled) {
                    Text(
                        "Lock after",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        LOCK_DELAYS.forEachIndexed { index, (label, millis) ->
                            SegmentedButton(
                                selected = settings.lockGraceMillis == millis,
                                onClick = { viewModel.setLockGrace(millis) },
                                shape = SegmentedButtonDefaults.itemShape(index, LOCK_DELAYS.size),
                            ) { Text(label) }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide from screenshots", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Blank the app in the task switcher and block screen recording.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.blockScreenshots,
                        onCheckedChange = viewModel::setBlockScreenshots,
                    )
                }
            }

            SettingsSection("Privacy") {
                Text(
                    "Everything stays on this phone.",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "OmniWallet has no account, no cloud and no analytics. Your library never " +
                        "leaves the device, and the app only talks to the hardware you pair with " +
                        "it. Card contents are never copied off your Flipper -- the app stores " +
                        "names and file paths so it knows what to ask the device to emit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "That library is stored encrypted, with the key held in this phone's secure " +
                        "hardware. Worth being precise about what that buys: it protects the file " +
                        "if someone copies it off the phone. It cannot protect you from software " +
                        "running on an unlocked, compromised device, because such software can ask " +
                        "for the key exactly as the app does. Guarding against that needs a master " +
                        "password you alone know -- and the matching risk that forgetting it loses " +
                        "the data for good.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("About") {
                Text("OmniWallet", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A remote control for external emulation hardware. The phone is the library; " +
                        "the Flipper is the radio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}
