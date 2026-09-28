package com.saschl.cameragps.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap


@RequiresApi(Build.VERSION_CODES.S)
class CameraDeviceCompanionService : CompanionDeviceService() {

    private companion object {
        /**
         * Associations whose camera stopped advertising while still connected.
         * Process-wide: the system may bind a new service instance between events.
         */
        val disappearedWhileConnected: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    }

    /**
     * Starts the service for a camera that was just seen advertising. It connects
     * directly (fast) instead of waiting for the slower background connection:
     * Fujifilm cameras advertise only briefly after switching on, and it makes Sony
     * cameras connect sooner too.
     */
    private fun startLocationSenderService(address: String?) {
        if (PreferencesManager.isAppEnabled(this)) {
            if (!LocationSenderService.hasLocationPermission(this)) {
                Timber.e("Location permission missing, not starting LocationSenderService for $address")
                return
            }

            val serviceIntent = Intent(this, LocationSenderService::class.java)
            serviceIntent.putExtra("address", address?.uppercase(Locale.getDefault()))
            serviceIntent.putExtra(ServiceCommandRouter.EXTRA_DIRECT_CONNECT, true)
            Timber.i("Starting LocationSenderService for address: $address")

            try {
                startForegroundService(serviceIntent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) when the
                // system refuses a background start; crashing the companion
                // callback would take the whole app process down with it.
                Timber.e(e, "Could not start LocationSenderService for $address")
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || missingPermissions()) {
            return
        }
        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Cannot post notifications, App may not work as expected")
        }
        Timber.i("Device appeared oldest API: $address")

        startLocationSenderService(address)
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingPermission")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Timber.i("Device appeared old API: ${associationInfo.id}")

            val address = associationInfo.deviceMacAddress?.toString() ?: return

            startLocationSenderService(address)
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    @SuppressLint("MissingPermission")
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        super.onDevicePresenceEvent(event)

        // when bluetooth is not permitted, we're done
        if (missingPermissions()) {
            Timber.e("Missing bluetooth permissions in  ${CameraDeviceCompanionService::class.java}")
            return
        }

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Cannot post notifications, App may not work as expected")
        }

        val associationId = event.associationId
        val deviceManager = getSystemService<CompanionDeviceManager>()
        val associatedDevices = deviceManager?.getMyAssociations()
        val associationInfo = associatedDevices?.find { it.id == associationId }
        val address = associationInfo?.deviceMacAddress?.toString()

        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) {
            disappearedWhileConnected.remove(associationId)

            Timber.i("Device appeared new API: ${event.associationId}")

            startLocationSenderService(address)
        }

        if (event.event == DevicePresenceEvent.EVENT_BLE_DISAPPEARED) {
            Timber.i("Device disappeared new API: ${event.associationId}")
            if (address == null) {
                Timber.e("Could not get address for disappeared device with association id: $associationId")
                return
            }
            // Some cameras (Fujifilm) stop advertising while connected. Like the
            // pre-Android 16 callbacks, treat the camera as gone only once the
            // link is down too (EVENT_BT_DISCONNECTED below).
            if (isGattConnected(address)) {
                Timber.i("Device $associationId stopped advertising but is still connected, keeping the session")
                disappearedWhileConnected.add(associationId)
                return
            }
            stopServiceOnDeviceDisappeared(address)
        }

        if (event.event == DevicePresenceEvent.EVENT_BT_DISCONNECTED &&
            disappearedWhileConnected.remove(associationId)
        ) {
            Timber.i("Device $associationId disconnected and is not advertising")
            if (address != null) stopServiceOnDeviceDisappeared(address)
        }
    }

    @SuppressLint("MissingPermission") // Checked by the caller (missingPermissions)
    private fun isGattConnected(address: String): Boolean {
        val manager = getSystemService<BluetoothManager>() ?: return false
        val device = runCatching { manager.adapter?.getRemoteDevice(address.uppercase()) }
            .getOrNull() ?: return false
        return manager.getConnectionState(device, BluetoothProfile.GATT) ==
                BluetoothProfile.STATE_CONNECTED
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Timber.i("Device disappeared oldest api: $address. Service will keep running until destroyed")
            stopServiceOnDeviceDisappeared(address)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            super.onDeviceDisappeared(associationInfo)
            Timber.i("Device disappeared old API: ${associationInfo.id}. Service will keep running until destroyed")
            stopServiceOnDeviceDisappeared(associationInfo.deviceMacAddress.toString())
        }
    }

    private fun stopServiceOnDeviceDisappeared(address: String) {
        // startService from the background throws when the target service is not
        // already running — and if it is not running there is nothing to shut
        // down anyway. isRunning can flip between check and call, so keep both.
        if (!LocationSenderService.isRunning) {
            Timber.i("LocationSenderService not running, ignoring disappearance of $address")
            return
        }
        val shutdownIntent = Intent(this, LocationSenderService::class.java).apply {
            action = SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN
        }
        shutdownIntent.putExtra("address", address.uppercase())
        try {
            startService(shutdownIntent)
        } catch (e: IllegalStateException) {
            Timber.w(e, "Could not deliver shutdown intent for $address")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("CDM started")
    }


    override fun onUnbind(intent: Intent?): Boolean {
        Timber.i("CompanionDeviceService onUnbind called. Will request shutdown of FGS")
        val shutdownIntent = Intent(this, LocationSenderService::class.java).apply {
            action = SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN

        }
     /*   shutdownIntent.putExtra("address", "all")
        startService(shutdownIntent)*/
        return super.onUnbind(intent)
    }


    /**
     * Check BLUETOOTH_CONNECT is granted and POST_NOTIFICATIONS is granted for devices running
     * Android 13 and above.
     */
    private fun missingPermissions(): Boolean = ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) != PackageManager.PERMISSION_GRANTED

}
