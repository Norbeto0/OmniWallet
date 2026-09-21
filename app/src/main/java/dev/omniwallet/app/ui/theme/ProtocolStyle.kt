package dev.omniwallet.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.omniwallet.core.domain.Protocol

/**
 * Per-protocol identity: a colour and an icon.
 *
 * Colour-coding the library the way a music player colour-codes genres is what
 * makes a list of a dozen cards scannable at arm's length. The hues are spaced
 * around the wheel but kept at similar chroma and lightness so they sit
 * together without one shouting, and each has a light and a dark variant so
 * neither theme ends up with a muddy chip.
 */
data class ProtocolStyle(
    val label: String,
    val icon: ImageVector,
    private val darkColor: Color,
    private val lightColor: Color,
    private val darkContainer: Color,
    private val lightContainer: Color,
) {
    fun color(dark: Boolean): Color = if (dark) darkColor else lightColor
    fun container(dark: Boolean): Color = if (dark) darkContainer else lightContainer
}

/**
 * Styles for every protocol.
 *
 * Exhaustive `when` on purpose: adding a protocol without giving it an
 * identity should fail to compile, not render a blank grey chip.
 */
fun Protocol.style(): ProtocolStyle = when (this) {
    Protocol.NFC -> ProtocolStyle(
        label = "NFC",
        icon = Icons.Outlined.Nfc,
        darkColor = Color(0xFFFF8200), lightColor = Color(0xFF8F4F00),
        darkContainer = Color(0xFF4A2A00), lightContainer = Color(0xFFFFDCC2),
    )
    Protocol.RFID_125K -> ProtocolStyle(
        label = "125 kHz RFID",
        icon = Icons.Outlined.SettingsInputAntenna,
        darkColor = Color(0xFFF2C94C), lightColor = Color(0xFF7A5A00),
        darkContainer = Color(0xFF423200), lightContainer = Color(0xFFFFE9A3),
    )
    Protocol.SUBGHZ -> ProtocolStyle(
        label = "Sub-GHz",
        icon = Icons.Outlined.Wifi,
        darkColor = Color(0xFF82D3E0), lightColor = Color(0xFF006874),
        darkContainer = Color(0xFF00363D), lightContainer = Color(0xFF9FEFFD),
    )
    Protocol.IBUTTON -> ProtocolStyle(
        label = "iButton",
        icon = Icons.Filled.Key,
        darkColor = Color(0xFF8FD99F), lightColor = Color(0xFF15692F),
        darkContainer = Color(0xFF00391A), lightContainer = Color(0xFFABF5B9),
    )
    Protocol.INFRARED -> ProtocolStyle(
        label = "Infrared",
        icon = Icons.Outlined.SettingsRemote,
        darkColor = Color(0xFFF2A9D2), lightColor = Color(0xFF8B3070),
        darkContainer = Color(0xFF4F0F3C), lightContainer = Color(0xFFFFD8EC),
    )
}

@Composable
fun Protocol.label(): String = style().label
