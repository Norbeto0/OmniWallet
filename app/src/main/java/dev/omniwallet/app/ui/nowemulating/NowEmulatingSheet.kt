package dev.omniwallet.app.ui.nowemulating

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.omniwallet.app.session.NowEmulating
import dev.omniwallet.app.ui.theme.LocalIsDarkTheme
import dev.omniwallet.app.ui.theme.style
import dev.omniwallet.core.domain.CredentialLocation
import kotlinx.coroutines.delay

/**
 * The expanded now-emulating surface.
 *
 * The bar owns this rather than navigating somewhere, which is what keeps it
 * self-contained: it can be tapped from any tab and leaves you exactly where
 * you were. Routing a persistent bar through navigation is what produced the
 * back-stack bug this replaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowEmulatingSheet(
    session: NowEmulating,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dark = LocalIsDarkTheme.current
    val style = session.credential.protocol.style()

    var elapsed by remember(session) { mutableLongStateOf(0L) }
    LaunchedEffect(session) {
        while (true) {
            elapsed = System.currentTimeMillis() - session.startedAtMillis
            delay(1_000)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(style.container(dark), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color(dark),
                    modifier = Modifier.size(44.dp),
                )
            }

            Text(
                text = session.credential.displayName,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "${style.label} · emulating for ${formatElapsed(elapsed)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            (session.credential.location as? CredentialLocation.FlipperFile)?.let { file ->
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = style.color(dark),
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Text("  Stop emulating", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
