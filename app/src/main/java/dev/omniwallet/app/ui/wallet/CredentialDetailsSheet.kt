package dev.omniwallet.app.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.omniwallet.core.domain.CredentialLocation
import dev.omniwallet.core.domain.StoredCredential
import dev.omniwallet.app.ui.theme.style

/**
 * Rename, favourite and hide.
 *
 * Renaming is local only. The file on the Flipper keeps its own name, which the
 * sheet shows underneath -- the brief is explicit that device files are not
 * mutated, and showing both makes that visible rather than merely true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailsSheet(
    credential: StoredCredential,
    onDismiss: () -> Unit,
    onRename: (String?) -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(credential.id) { mutableStateOf(credential.customName ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(credential.displayName, style = MaterialTheme.typography.headlineSmall)

            Text(
                text = credential.protocol.style().label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name in this app") },
                placeholder = { Text(credential.discoveredName) },
                supportingText = { Text("Leave empty to use the name on the device") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            (credential.location as? CredentialLocation.FlipperFile)?.let { file ->
                Column {
                    Text(
                        "On the device",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = file.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            if (!credential.present) {
                Text(
                    "Not found on the device during the last refresh. It is kept here in case " +
                        "the device was simply switched off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleFavourite) {
                    Icon(
                        if (credential.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = null,
                    )
                    Text(
                        text = if (credential.favourite) "  Unfavourite" else "  Favourite",
                    )
                }
                OutlinedButton(onClick = onToggleHidden) {
                    Icon(
                        if (credential.hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                    )
                    Text(if (credential.hidden) "  Unhide" else "  Hide")
                }
            }

            Button(
                onClick = {
                    onRename(name.takeIf { it.isNotBlank() })
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
