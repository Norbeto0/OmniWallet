package dev.omniwallet.transport.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Which runtime permissions BLE needs, which is not the same question on every
 * Android version.
 *
 * Below API 31 the scan permissions are implicit but `ACCESS_FINE_LOCATION` is
 * mandatory, and without it the platform returns an empty scan result list with
 * no error at all. That silent-empty behaviour is the single most common reason
 * a BLE app looks broken while appearing to work, so it is treated as a
 * first-class state here rather than left to fail quietly.
 */
object BlePermissions {

    /** Permissions that must be granted at runtime on this device's API level. */
    val required: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missing(context: Context): List<String> = required.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /** Everything the UI needs to decide whether scanning can even be attempted. */
    data class Readiness(
        val missingPermissions: List<String>,
        val bluetoothEnabled: Boolean,
    ) {
        val canScan: Boolean get() = missingPermissions.isEmpty() && bluetoothEnabled
    }

    fun readiness(context: Context): Readiness =
        Readiness(missing(context), isBluetoothEnabled(context))
}
