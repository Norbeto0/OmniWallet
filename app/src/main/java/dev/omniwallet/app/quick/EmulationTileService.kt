package dev.omniwallet.app.quick

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.omniwallet.app.session.EmulationController
import dev.omniwallet.app.session.EmulationService
import dev.omniwallet.app.session.QuickActions
import dev.omniwallet.core.domain.QuickActionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * A Quick Settings toggle for the card you are most likely to want.
 *
 * One button, so it acts on one thing: whatever is emulating, else the top of
 * the wallet's own ordering. Borrowing that ordering rather than inventing a
 * second rule is what stops the tile and the list disagreeing about which card
 * is "the" card.
 *
 * `unlockAndRun` wraps the work deliberately. A tile is reachable from the lock
 * screen, and a control that opens a door without the phone being unlocked is
 * not something to ship whatever the convenience argument.
 */
@AndroidEntryPoint
class EmulationTileService : TileService() {

    @Inject
    lateinit var quickActions: QuickActions

    @Inject
    lateinit var emulation: EmulationController

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        refresh()
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Everything below the unlock is on the far side of authentication,
        // which is the point.
        unlockAndRun {
            scope?.launch {
                val running = emulation.nowEmulating.value
                val intent = if (running != null) {
                    EmulationService.stopIntent(this@EmulationTileService, QuickActionPolicy.Source.TILE)
                } else {
                    val target = quickActions.quickTarget() ?: run {
                        withContext(Dispatchers.Main) { refresh() }
                        return@launch
                    }
                    EmulationService.toggleIntent(
                        this@EmulationTileService,
                        target.id,
                        QuickActionPolicy.Source.TILE,
                    )
                }
                EmulationService.start(this@EmulationTileService, intent)
                withContext(Dispatchers.Main) { refresh() }
            }
        }
    }

    /**
     * Show what the tile will do next.
     *
     * The label carries the card's name rather than a generic "Emulate",
     * because a tile that does not say what it is about to emit is a tile
     * nobody should tap.
     */
    private fun refresh() {
        val tile = qsTile ?: return
        val running = emulation.nowEmulating.value

        tile.state = if (running != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = running?.credential?.displayName ?: "OmniWallet"
        tile.contentDescription = running
            ?.let { "Stop emulating ${it.credential.displayName}" }
            ?: "Emulate your most recent card"
        tile.updateTile()
    }
}
