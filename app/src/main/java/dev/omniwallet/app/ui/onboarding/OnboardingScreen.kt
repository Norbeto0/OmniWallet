package dev.omniwallet.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.omniwallet.transport.ble.BlePermissions
import kotlinx.coroutines.launch

/**
 * Three screens, shown once.
 *
 * The first one is the one that matters, and it has two jobs. It says what
 * this app is -- a vault for the credentials you carry, not a Flipper
 * companion; the official app is the companion and is better at it -- and it
 * heads off the single most likely misunderstanding, which is that the *phone*
 * is emulating the card. It is not, and Android cannot: HCE presents neither
 * an arbitrary UID nor Mifare Classic, which is the entire reason the external
 * device exists. Someone who installs this expecting a card emulator will
 * conclude it is broken.
 *
 * The other two exist because both are things the user must do on hardware the
 * app cannot reach: switch Bluetooth on at the Flipper, and grant a permission
 * whose absence produces a silent empty scan rather than an error.
 */
private data class Pane(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val panes = listOf(
    Pane(
        icon = Icons.Outlined.Lock,
        title = "A vault for the keys you carry",
        body = "Your own names for the doors you can open, encrypted on this phone and locked " +
            "behind your fingerprint. It does not emulate anything itself — Android cannot " +
            "present an arbitrary card ID, which is why your Flipper exists. This is the map; " +
            "the Flipper is the key.",
    ),
    Pane(
        icon = Icons.Outlined.Bluetooth,
        title = "Pair your Flipper",
        body = "On the Flipper: Settings → Bluetooth → ON. The first connection shows a " +
            "six-digit code on its screen for you to type into Android. Pairing is " +
            "required — the channel this app uses refuses an unauthenticated link.",
    ),
    Pane(
        icon = Icons.Outlined.Shield,
        title = "One permission, then you are done",
        body = "Android needs Nearby devices before it will return any Bluetooth results " +
            "at all. Without it a scan finds nothing and reports no error, which looks " +
            "exactly like a broken app. Nothing here leaves your phone, ever.",
    ),
)

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val pagerState = rememberPagerState(pageCount = { panes.size })
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Finish either way. A refusal is the user's call, and the Device
        // screen already explains and re-offers it; trapping someone on an
        // onboarding pane until they say yes is not a pattern worth having.
        viewModel.complete()
        onFinished()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                PaneBody(panes[page])
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 20.dp),
            ) {
                repeat(panes.size) { index ->
                    Box(
                        Modifier
                            .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                            .background(
                                if (index == pagerState.currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                CircleShape,
                            ),
                    )
                }
            }

            val last = pagerState.currentPage == panes.lastIndex
            Button(
                onClick = {
                    if (last) {
                        permissionLauncher.launch(onboardingPermissions().toTypedArray())
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (last) "Grant and continue" else "Next")
            }

            TextButton(
                onClick = {
                    viewModel.complete()
                    onFinished()
                },
            ) { Text("Skip") }
        }
    }
}

@Composable
private fun PaneBody(pane: Pane) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                pane.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Box(Modifier.height(28.dp).width(1.dp))
        Text(
            text = pane.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.height(12.dp).width(1.dp))
        Text(
            text = pane.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Everything asked for in one prompt.
 *
 * Location is not included: the nearby ranking asks for it at the moment
 * someone tags a place, which is when the reason for it is obvious. Asking on
 * first launch, before the user has any idea why a key manager wants their
 * location, is how an app gets a permanent denial.
 */
private fun onboardingPermissions(): List<String> = buildList {
    addAll(BlePermissions.required)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
