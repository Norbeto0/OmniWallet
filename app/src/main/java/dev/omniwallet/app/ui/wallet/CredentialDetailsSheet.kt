package dev.omniwallet.app.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.omniwallet.app.ui.theme.style
import dev.omniwallet.core.domain.CredentialLocation
import dev.omniwallet.core.domain.StoredCredential

/**
 * Rename, favourite, hide, and tag where the card gets used.
 *
 * Renaming is local only. The file on the Flipper keeps its own name, which the
 * sheet shows underneath -- the brief is explicit that device files are not
 * mutated, and showing both makes that visible rather than merely true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailsSheet(
    credential: StoredCredential,
    locating: Boolean,
    onDismiss: () -> Unit,
    onRename: (String?) -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleHidden: () -> Unit,
    onTagHere: () -> Unit,
    onRenamePlace: (String) -> Unit,
    onClearPlace: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(credential.id) { mutableStateOf(credential.customName ?: "") }
    // Keyed on the place itself, so the field picks up a label that was just
    // written by tagging rather than holding the value from before the tap.
    var placeName by remember(credential.place) {
        mutableStateOf(credential.place?.label ?: "")
    }

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

            PlaceSection(
                credential = credential,
                placeName = placeName,
                locating = locating,
                onPlaceNameChange = { placeName = it },
                onTagHere = onTagHere,
                onClearPlace = onClearPlace,
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
                    // Only when there is a place to rename, and only when it
                    // changed: an untagged card has no coordinate, so a name on
                    // its own would be a place that cannot be found or removed.
                    credential.place?.let { existing ->
                        if (placeName.isNotBlank() && placeName != existing.label) {
                            onRenamePlace(placeName)
                        }
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

/**
 * Tag a card to a place, or untag it.
 *
 * Two states rather than one form. Untagged shows a single button, because
 * there is nothing to name yet; tagged shows the name and how to move or remove
 * the pin. That avoids the half-filled middle state -- a place with a name and
 * no coordinate -- which the database cannot store and the user cannot see.
 */
@Composable
private fun PlaceSection(
    credential: StoredCredential,
    placeName: String,
    locating: Boolean,
    onPlaceNameChange: (String) -> Unit,
    onTagHere: () -> Unit,
    onClearPlace: () -> Unit,
) {
    val place = credential.place

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Place",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (place == null) {
            Text(
                "Tag where you use this and it will be suggested first when you are there. " +
                    "The position is stored on this phone only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onTagHere, enabled = !locating) {
                if (locating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  Finding you…")
                } else {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null)
                    Text("  Tag this place")
                }
            }
        } else {
            OutlinedTextField(
                value = placeName,
                onValueChange = onPlaceNameChange,
                label = { Text("Place name") },
                placeholder = { Text(place.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onTagHere, enabled = !locating) {
                    Text(if (locating) "Finding you…" else "Move pin here")
                }
                TextButton(onClick = onClearPlace) { Text("Remove") }
            }
        }
    }
}
