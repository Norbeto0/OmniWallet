package dev.omniwallet.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Tonal palettes generated from the Flipper's orange, #FF8200.
 *
 * These are proper Material 3 ramps rather than a handful of hand-picked
 * colours: primary/secondary/tertiary plus the neutral surface family, so the
 * `surfaceContainer*` roles that Pixel surfaces lean on all resolve correctly.
 * Secondary is a desaturated companion of the same hue; tertiary is the cyan
 * that reads as the "signal" colour across the app.
 */

// Primary -- Flipper orange.
private val Orange10 = Color(0xFF2E1500)
private val Orange20 = Color(0xFF4C2700)
private val Orange30 = Color(0xFF6D3A00)
private val Orange40 = Color(0xFF8F4F00)
private val Orange50 = Color(0xFFB36400)
private val Orange60 = Color(0xFFD87B00)
private val Orange70 = Color(0xFFFF8200)
private val Orange80 = Color(0xFFFFB77A)
private val Orange90 = Color(0xFFFFDCC2)
private val Orange95 = Color(0xFFFFEDE2)

// Secondary -- the same hue, pulled back so it can sit beside primary.
private val Warm20 = Color(0xFF3F2A14)
private val Warm30 = Color(0xFF574029)
private val Warm40 = Color(0xFF71573F)
private val Warm80 = Color(0xFFE2BFA0)
private val Warm90 = Color(0xFFFFDCC2)

// Tertiary -- cyan, the app's signal colour.
private val Cyan20 = Color(0xFF00363D)
private val Cyan30 = Color(0xFF004F58)
private val Cyan40 = Color(0xFF006874)
private val Cyan80 = Color(0xFF82D3E0)
private val Cyan90 = Color(0xFF9FEFFD)

// Neutrals, warmed very slightly so surfaces do not read as blue-grey next to
// the orange.
private val Neutral6 = Color(0xFF12100E)
private val Neutral10 = Color(0xFF1A1714)
private val Neutral12 = Color(0xFF1F1B18)
private val Neutral17 = Color(0xFF2A2420)
private val Neutral20 = Color(0xFF322C27)
private val Neutral22 = Color(0xFF383029)
private val Neutral24 = Color(0xFF3D352E)
private val Neutral90 = Color(0xFFEDE0D9)
private val Neutral95 = Color(0xFFF7EBE3)
private val Neutral98 = Color(0xFFFFF8F5)
private val NeutralVariant30 = Color(0xFF52443C)
private val NeutralVariant60 = Color(0xFFA08D82)
private val NeutralVariant80 = Color(0xFFD7C2B6)
private val NeutralVariant90 = Color(0xFFF3DFD2)

private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)
private val Red90 = Color(0xFFFFDAD6)
private val Red20 = Color(0xFF690005)

/** Dark first, as the brief asks -- and the mode the app is designed in. */
val FlipperDarkScheme = darkColorScheme(
    primary = Orange70,
    onPrimary = Orange10,
    primaryContainer = Orange30,
    onPrimaryContainer = Orange90,
    inversePrimary = Orange40,

    secondary = Warm80,
    onSecondary = Warm20,
    secondaryContainer = Warm30,
    onSecondaryContainer = Warm90,

    tertiary = Cyan80,
    onTertiary = Cyan20,
    tertiaryContainer = Cyan30,
    onTertiaryContainer = Cyan90,

    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceContainerLowest = Color(0xFF0D0B0A),
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
    surfaceBright = Neutral24,
    surfaceDim = Neutral6,

    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,

    error = Red80,
    onError = Red20,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Red90,
)

val FlipperLightScheme = lightColorScheme(
    primary = Orange40,
    onPrimary = Color.White,
    primaryContainer = Orange90,
    onPrimaryContainer = Orange10,
    inversePrimary = Orange80,

    secondary = Warm40,
    onSecondary = Color.White,
    secondaryContainer = Warm90,
    onSecondaryContainer = Warm20,

    tertiary = Cyan40,
    onTertiary = Color.White,
    tertiaryContainer = Cyan90,
    onTertiaryContainer = Cyan20,

    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Neutral95,
    surfaceContainer = Color(0xFFFCF0E9),
    surfaceContainerHigh = Color(0xFFF6EAE3),
    surfaceContainerHighest = Neutral90,

    outline = Color(0xFF85736B),
    outlineVariant = NeutralVariant80,

    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red20,
)
