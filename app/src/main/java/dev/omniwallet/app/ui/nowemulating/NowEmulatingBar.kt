package dev.omniwallet.app.ui.nowemulating

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omniwallet.app.session.NowEmulating
import dev.omniwallet.app.ui.theme.LocalIsDarkTheme
import dev.omniwallet.app.ui.theme.style
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The now-emulating bar: a now-playing bar for radio instead of audio.
 *
 * Sits above the navigation bar and survives navigation, because it is driven
 * by a singleton rather than by any screen's state. That is the reason it
 * exists in this shape -- while a card is being emitted, the fact that it is
 * being emitted is the most important thing on screen no matter where you have
 * wandered to.
 */
@Composable
fun NowEmulatingBar(
    nowEmulating: NowEmulating?,
    onStop: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Retained so the bar keeps rendering its last card through the exit
    // animation instead of collapsing to blank mid-slide.
    var lastSession by remember { mutableStateOf<NowEmulating?>(null) }
    LaunchedEffect(nowEmulating) { nowEmulating?.let { lastSession = it } }

    AnimatedVisibility(
        visible = nowEmulating != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val session = lastSession ?: return@AnimatedVisibility
        val dark = LocalIsDarkTheme.current
        val style = session.credential.protocol.style()

        var elapsed by remember(session) { mutableLongStateOf(0L) }
        LaunchedEffect(session) {
            while (true) {
                elapsed = System.currentTimeMillis() - session.startedAtMillis
                delay(1_000)
            }
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            color = style.container(dark),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(style.color(dark).copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = style.color(dark),
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.credential.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Emulating · ${formatElapsed(elapsed)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                FilledIconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = style.color(dark),
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop emulating")
                }
            }
        }
    }
}

internal fun formatElapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
