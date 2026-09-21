package dev.omniwallet.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** How the app picks light or dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Whether the current scheme is dark, for the per-protocol colours, which pick
 * their own variants rather than going through the scheme.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** Whether this device can do wallpaper-derived colour at all. */
val dynamicColorAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The app's theme.
 *
 * Flipper orange is the default identity, because an app that turns blue on a
 * blue wallpaper stops looking like itself. [dynamicColor] opts into true
 * Material You for people who would rather match their phone; it is ignored
 * below API 31, where the platform cannot supply it.
 *
 * On `MaterialTheme` rather than `MaterialExpressiveTheme`: the expressive
 * classes ship inside material3 1.4.0 but are still `internal`, so they are not
 * callable from here -- their presence in the artifact is not the same as
 * public API, which is worth remembering. Pulling an alpha of material3 into a
 * credential manager to get a theme wrapper is a poor trade, and little is
 * lost: the Pixel character comes from the tonal palette, the generous corner
 * radii in [OmniShapes] and the `surfaceContainer` layering, all of which are
 * stable API.
 */
@Composable
fun OmniWalletTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && dynamicColorAvailable ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FlipperDarkScheme
        else -> FlipperLightScheme
    }

    CompositionLocalProvider(LocalIsDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = OmniShapes,
            content = content,
        )
    }
}
