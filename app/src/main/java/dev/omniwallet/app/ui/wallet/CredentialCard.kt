package dev.omniwallet.app.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omniwallet.app.ui.theme.LocalIsDarkTheme
import dev.omniwallet.app.ui.theme.style
import dev.omniwallet.core.domain.StoredCredential
import java.util.concurrent.TimeUnit

/**
 * One card in the library.
 *
 * The whole surface is the emulate button. Tapping a card to use it is the
 * entire point of the app -- the brief's "tap the phone instead of navigating
 * the device's menus" -- so it gets the largest possible target, and the
 * secondary actions live behind a long press rather than competing for space.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CredentialCard(
    credential: StoredCredential,
    enabled: Boolean,
    emulating: Boolean,
    onEmulate: () -> Unit,
    onDetails: () -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Replaces the usual second line. Used by the Nearby section to say how far
     * away the card's place is, which is the only reason it is in that section
     * at all -- a suggestion that does not say why it is being made is just an
     * unexplained reordering.
     */
    subtitleOverride: String? = null,
) {
    val dark = LocalIsDarkTheme.current
    val style = credential.protocol.style()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (emulating) {
                style.container(dark)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = onEmulate,
                    onLongClick = onDetails,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
                // Dimmed rather than hidden when the device is away: the card
                // is still yours, it just cannot be used this second.
                .alpha(if (enabled) 1f else 0.55f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(style.container(dark), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // While running, the protocol glyph becomes a stop square, so
                // the card visibly advertises what a second tap will do. A
                // control that changes state should look like it changed.
                Icon(
                    imageVector = if (emulating) Icons.Filled.Stop else style.icon,
                    contentDescription = null,
                    tint = style.color(dark),
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = credential.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // The override loses to a live state: "emulating now" and
                    // "connect to use" are things the user needs, and distance
                    // is a nicety.
                    text = credential.subtitle(enabled, emulating, subtitleOverride),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onToggleFavourite) {
                Icon(
                    imageVector = if (credential.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (credential.favourite) "Remove from favourites" else "Add to favourites",
                    tint = if (credential.favourite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** The one line under the name: state first, then protocol and recency. */
private fun StoredCredential.subtitle(
    enabled: Boolean,
    emulating: Boolean,
    override: String?,
): String {
    val style = protocol.style()
    return when {
        emulating -> "Emulating now · tap to stop"
        !present -> "${style.label} · not on the device"
        !enabled -> "${style.label} · connect to use"
        override != null -> override
        else -> buildString {
            append(style.label)
            lastUsedAtMillis?.let { append(" · ").append(relativeTime(it)) }
        }
    }
}

private fun relativeTime(millis: Long): String {
    val delta = (System.currentTimeMillis() - millis).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}
