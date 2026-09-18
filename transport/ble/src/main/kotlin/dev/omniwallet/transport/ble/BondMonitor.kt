package dev.omniwallet.transport.ble

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A bond state transition for one device. */
data class BondEvent(
    val address: String,
    val previousState: Int,
    val newState: Int,
) {
    /**
     * Bonding was attempted and collapsed back to unbonded.
     *
     * This is the signature of the stale-bond problem: the Flipper initiates a
     * security request and terminates the link when pairing fails (gap.c), and
     * switching firmware -- official to Momentum, say -- regenerates the device
     * keys so the bond Android still holds no longer matches. Retrying cannot
     * fix it; only forgetting the device and pairing again can.
     */
    val isPairingFailure: Boolean
        get() = previousState == BluetoothDevice.BOND_BONDING &&
            newState == BluetoothDevice.BOND_NONE

    val isBonded: Boolean get() = newState == BluetoothDevice.BOND_BONDED

    /**
     * Bonding is in progress. Worth naming because the Flipper shows a 6-digit
     * code the user must type (bt.c), so this state can legitimately last tens
     * of seconds and must not be mistaken for a stall.
     */
    val isBonding: Boolean get() = newState == BluetoothDevice.BOND_BONDING
}

/** Observes Android's bond-state broadcasts. */
class BondMonitor(private val context: Context) {

    fun events(): Flow<BondEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                trySend(
                    BondEvent(
                        address = device.address,
                        previousState = intent.getIntExtra(
                            BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                            BluetoothDevice.BOND_NONE,
                        ),
                        newState = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE,
                        ),
                    ),
                )
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
        )
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }
}
