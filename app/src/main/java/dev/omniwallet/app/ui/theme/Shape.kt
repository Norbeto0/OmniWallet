package dev.omniwallet.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Pixel-scale roundness.
 *
 * Noticeably softer than the Material 3 baseline, which is what gives Pixel
 * surfaces their character: cards and sheets read as pebbles rather than
 * panels.
 */
val OmniShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)
