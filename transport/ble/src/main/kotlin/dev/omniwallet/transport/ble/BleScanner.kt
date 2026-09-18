package dev.omniwallet.transport.ble

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelUuid
import dev.omniwallet.core.domain.DeviceKind
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import java.util.UUID

/**
 * Scans for BLE devices.
 *
 * Knows nothing about any particular device: callers supply the service UUIDs
 * worth looking for via [knownServices]. Keeping device specifics out of the
 * transport is what lets a second backend slot in without reshaping this layer.
 *
 * Filtering keys on the **service UUID**, never the device name. Momentum lets
 * users rename their Flipper, but the serial service UUID is byte-identical
 * across official firmware, Unleashed and Momentum, so the UUID is the only
 * stable identifier.
 */
class BleScanner(
    private val context: Context,
    private val knownServices: Map<UUID, DeviceKind>,
) {

    class ScanFailedException(val errorCode: Int) :
        Exception("BLE scan failed with error code $errorCode")

    private val servicesByString: Map<String, DeviceKind> =
        knownServices.mapKeys { it.key.toString().lowercase() }

    /**
     * Emits each advertisement seen. A device reappears as its RSSI changes;
     * de-duplication is left to the caller, because the diagnostics screen
     * wants the raw stream and the wallet does not.
     *
     * @param filtered when true, restrict to [knownServices]. When false,
     *   report everything in range -- which is how a user on real hardware can
     *   tell us what their device actually advertises, something that cannot be
     *   confirmed without a device in hand.
     */
    @SuppressLint("MissingPermission") // callers gate on BlePermissions first
    fun scan(filtered: Boolean = true): Flow<DiscoveredDevice> = callbackFlow {
        val scanner = BluetoothLeScannerCompat.getScanner()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setUseHardwareFilteringIfSupported(filtered)
            .build()

        val filters = if (filtered) {
            knownServices.keys.map {
                ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
            }
        } else {
            emptyList()
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toDiscoveredDevice())
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { trySend(it.toDiscoveredDevice()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }
        }

        scanner.startScan(filters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toDiscoveredDevice(): DiscoveredDevice {
        val services = scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty()
        return DiscoveredDevice(
            address = device.address,
            name = scanRecord?.deviceName,
            rssi = rssi,
            advertisedServices = services,
            kind = services.firstNotNullOfOrNull { servicesByString[it] },
        )
    }
}
