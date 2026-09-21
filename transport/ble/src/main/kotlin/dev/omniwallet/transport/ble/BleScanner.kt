package dev.omniwallet.transport.ble

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelUuid
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
 * Knows nothing about any particular device: callers supply [targets]. Keeping
 * device specifics out of the transport is what lets a second backend slot in
 * without reshaping this layer.
 *
 * Filtering keys on the advertised service UUID, never the device name --
 * Momentum lets users rename their Flipper. Note that the advertised UUID is
 * not necessarily the service you later connect to: a Flipper advertises a
 * 16-bit `0x3080 | hw_color` and only exposes its 128-bit serial service once
 * connected. Filtering on the latter matches nothing, which is why [targets]
 * carries masks rather than bare UUIDs.
 */
class BleScanner(
    private val context: Context,
    private val targets: List<ScanTarget>,
) {

    class ScanFailedException(val errorCode: Int) :
        Exception("BLE scan failed with error code $errorCode")

    /**
     * Emits each advertisement seen. A device reappears as its RSSI changes;
     * de-duplication is left to the caller, because the diagnostics screen
     * wants the raw stream and the wallet does not.
     *
     * @param filtered when true, restrict to [targets]; when false, report
     *   everything in range, which is how a user on real hardware can tell us
     *   what their device actually advertises.
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
            targets.map { target ->
                ScanFilter.Builder()
                    .setServiceUuid(
                        ParcelUuid(target.serviceUuid),
                        target.mask?.let { ParcelUuid(it) },
                    )
                    .build()
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
        val advertised: List<UUID> = scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
        val advertisedName = scanRecord?.deviceName
        return DiscoveredDevice(
            address = device.address,
            name = advertisedName,
            rssi = rssi,
            advertisedServices = advertised.map { it.toString().lowercase() },
            kind = advertised.firstNotNullOfOrNull { uuid ->
                targets.firstOrNull { it.identifies(uuid, advertisedName) }?.kind
            },
        )
    }
}
