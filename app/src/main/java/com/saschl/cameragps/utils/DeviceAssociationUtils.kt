package com.saschl.cameragps.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.service.isFujifilmAdvertisement
import com.saschl.cameragps.service.isSonyAdvertisement
import com.saschl.cameragps.service.nameOrNull
import com.saschl.cameragps.service.toAssociatedDevice
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import java.util.concurrent.Executor

object DeviceAssociationUtils {

    @SuppressLint("MissingPermission")
    fun getAssociationResult(intent: Intent, adapter: BluetoothAdapter?): AssociatedDeviceCompat? {
        var result: AssociatedDeviceCompat? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result = intent.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )?.toAssociatedDevice(adapter)
        } else {
            // Below Android 33 the result returns either a BLE ScanResult, a
            // Classic BluetoothDevice or a Wifi ScanResult
            // In our case we are looking for our BLE GATT server so we can cast directly
            // to the BLE ScanResult
            // FIXME for some reason it does return a BluetoothDevice so we need to handle that
            @Suppress("DEPRECATION")
            try {
                val scanResult =
                    intent.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
                if (scanResult != null) {
                    result = AssociatedDeviceCompat(
                        id = -1, //no id
                        address = scanResult.address ?: "N/A",
                        name = scanResult.nameOrNull() ?: "N/A",
                        device = scanResult,
                    )
                }
            } catch (_: ClassCastException) {
                // Not a BLE device
                Timber.w("API level is below 33 but it is not a BluetoothDevice. Trying with scanresult")
                val scanResult =
                    intent.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
                result = scanResult?.let {
                    AssociatedDeviceCompat(
                        id = it.advertisingSid, //no id
                        address = it.device.address ?: "N/A",
                        name = it.device.nameOrNull() ?: "N/A",
                        device = it.device,
                        isFujifilm = it.isFujifilmAdvertisement(),
                        isSony = it.isSonyAdvertisement(),
                    )
                }
            }
        }
        return result
    }

    suspend fun requestDeviceAssociation(
        deviceManager: CompanionDeviceManager
    ): IntentSender {
        // Cameras are recognized by the Bluetooth company ID in their advertised
        // manufacturer data: Sony and Fujifilm. The chooser lists devices matching either.
        fun manufacturerFilter(companyId: Int) = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setManufacturerData(companyId, byteArrayOf()).build())
            .build()

        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
            .addDeviceFilter(manufacturerFilter(SonyBluetoothConstants.COMPANY_ID))
            .addDeviceFilter(manufacturerFilter(FujifilmBluetoothConstants.COMPANY_ID))
            .build()

        val result = CompletableDeferred<IntentSender>()

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                Timber.i("Association pending")
                result.complete(intentSender)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Timber.i("Association created: ${associationInfo.displayName} (${associationInfo.id})")
                }
            }

            override fun onFailure(errorMessage: CharSequence?) {
                result.completeExceptionally(
                    IllegalStateException(
                        errorMessage?.toString().orEmpty()
                    )
                )
            }

            override fun onDeviceFound(intentSender: IntentSender) {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    Timber.i("Device found")
                    // On Android <= 12 we get the device found callback instead of association pending
                    result.complete(intentSender)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val executor = Executor { it.run() }
            deviceManager.associate(pairingRequest, executor, callback)
        } else {
            deviceManager.associate(pairingRequest, callback, null)
        }

        return result.await()
    }
}
