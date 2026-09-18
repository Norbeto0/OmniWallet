package dev.omniwallet.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-first, as the brief asks. The accent is deliberately the Flipper's
// orange so device state reads at a glance.
private val Orange = Color(0xFFFF8200)
private val OrangeDark = Color(0xFFC25E00)

private val DarkColors = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF1A1A1A),
    secondary = Color(0xFF7FD1FF),
    background = Color(0xFF101014),
    surface = Color(0xFF17171C),
    surfaceVariant = Color(0xFF23232A),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = OrangeDark,
    secondary = Color(0xFF00629E),
)

@Composable
fun OmniWalletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
