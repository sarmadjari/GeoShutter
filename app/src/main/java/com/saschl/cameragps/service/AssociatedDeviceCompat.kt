package com.saschl.cameragps.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.util.Locale

/**
 * Wrapper for the different type of classes the CDM returns
 */
data class AssociatedDeviceCompat(
    val id: Int,
    val address: String,
    var name: String,
    val device: BluetoothDevice?,
    var isPaired: Boolean = true
)


@SuppressLint("MissingPermission")
internal fun CompanionDeviceManager.getAssociatedDevices(adapter: BluetoothAdapter): List<AssociatedDeviceCompat> {
    // One bond lookup for the whole list. Unknown (Bluetooth off or the Nearby
    // devices permission missing) counts as paired, so no false warning shows.
    val bondedAddresses = if (adapter.isEnabled) adapter.bondedAddressesOrNull() else null
    fun isPaired(address: String) = bondedAddresses?.contains(address.uppercase()) ?: true

    val associatedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        myAssociations.map {
            it.toAssociatedDevice(adapter).apply {
                isPaired = isPaired(address)
            }
        }
    } else {
        // Before Android 34 we can only get the MAC.
        @Suppress("DEPRECATION")
        associations.map {
            val deviceAddress = it.uppercase(Locale.getDefault())
            AssociatedDeviceCompat(
                id = -1,
                address = deviceAddress,
                name = adapter.cachedNameOrNull(deviceAddress) ?: "N/A",
                device = null,
                isPaired = isPaired(deviceAddress),
            )
        }
    }
    return associatedDevice
}


@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun AssociationInfo.toAssociatedDevice(adapter: BluetoothAdapter?): AssociatedDeviceCompat {
    val address = deviceMacAddress?.toString()?.uppercase(Locale.getDefault())
    // Android 13 stores no displayName for chooser-based associations (only
    // self-managed ones get it; Android 14+ persists the chooser name), so fall
    // back to the Bluetooth stack's cached name for the bonded device.
    val cachedName = address?.let { adapter?.cachedNameOrNull(it) }
    return AssociatedDeviceCompat(
        id = id,
        address = address ?: "N/A",
        name = displayName?.toString()?.takeIf { it.isNotBlank() } ?: cachedName ?: "N/A",
        device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            associatedDevice?.bleDevice?.device
        } else {
            null
        },
    )
}

/**
 * Uppercased addresses of the bonded devices, or null when they cannot be read.
 * The permission screen can be skipped ("Continue anyway"), so the Nearby
 * devices permission may be missing and must not crash the app.
 */
@SuppressLint("MissingPermission")
internal fun BluetoothAdapter.bondedAddressesOrNull(): Set<String>? = try {
    bondedDevices?.mapTo(mutableSetOf()) { it.address.uppercase() }
} catch (_: SecurityException) {
    Timber.w("Cannot read bonded devices: Nearby devices permission missing")
    null
}

/** The name Bluetooth cached for [address], or null if unknown or not readable. */
internal fun BluetoothAdapter.cachedNameOrNull(address: String): String? = try {
    getRemoteDevice(address.uppercase()).nameOrNull()
} catch (_: IllegalArgumentException) {
    null
}

/** The device name, or null if unknown or the Nearby devices permission is missing. */
@SuppressLint("MissingPermission")
internal fun BluetoothDevice.nameOrNull(): String? = try {
    name
} catch (_: SecurityException) {
    null
}
