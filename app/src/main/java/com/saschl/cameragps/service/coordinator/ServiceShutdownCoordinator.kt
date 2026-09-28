package com.saschl.cameragps.service.coordinator

import android.annotation.SuppressLint
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.saschl.cameragps.service.transport.AndroidBleTransport
import timber.log.Timber

class ServiceShutdownCoordinator(
    private val deviceDao: CameraDeviceDAO,
    private val transport: AndroidBleTransport,
    private val onShutdownRequested: (Int?) -> Unit,
) {
    @SuppressLint("MissingPermission")
    suspend fun handleNoAddress(startId: Int) {
        if (deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.i("No always-on devices found, shutting down service")
            onShutdownRequested(startId)
            return
        }

        runCatching {
            deviceDao.getAllCameraDevices()
                .filter { it.alwaysOnEnabled }
                .forEach { device ->
                    runCatching {
                        transport.connect(device.mac)
                    }.onFailure { handleGattConnectionFailure(startId, device) }
                }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleGattConnectionFailure(startId: Int, cameraDevice: CameraDevice) {
        Timber.e("Failed to connect to device ${cameraDevice.deviceName}, bluetooth is likely disabled")
    }

    @SuppressLint("MissingPermission")
    suspend fun handleShutdownRequest(address: String, startId: Int) {
        Timber.i("Shutdown requested for device $address")

        if (address == "all") {
            handleShutdownAllDevices(startId)
            return
        }

        // Sometimes false "disappeared" events appear, so keep the device active if it was always on.
        if (!deviceDao.isDeviceAlwaysOnEnabled(address)) {
            transport.pauseDevice(address)
        }
        if (transport.connectedCount() == 0 && deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.d("No connected or always on cameras remaining, shutting down service")
            onShutdownRequested(startId)
        }
    }

    /**
     * Stop the service when no camera connected and none is Always On. A newer start
     * command (a camera Android reported meanwhile) keeps it running; stopping cleans
     * up pending connections in onDestroy.
     */
    suspend fun stopIfIdle(startId: Int) {
        if (transport.connectedCount() > 0 || deviceDao.getAlwaysOnEnabledDeviceCount() > 0) return
        Timber.i("No saved camera answered, waiting until Android reports one")
        onShutdownRequested(startId)
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleShutdownAllDevices(startId: Int) {
        if (deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.i("No always-on devices found, disconnecting all cameras and shutting down service")
            transport.disconnectAll()
            onShutdownRequested(startId)
        } else {
            Timber.i("At least one always-on device found, not shutting down service")
        }
    }
}
