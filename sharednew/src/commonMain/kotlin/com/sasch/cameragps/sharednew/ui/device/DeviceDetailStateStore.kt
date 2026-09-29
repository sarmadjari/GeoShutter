package com.sasch.cameragps.sharednew.ui.device

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface DeviceDetailDataSource {
    suspend fun ensureDeviceExists(deviceId: String, deviceName: String? = null)
    suspend fun isDeviceEnabled(deviceId: String): Boolean
    suspend fun isAlwaysOnEnabled(deviceId: String): Boolean
    suspend fun isRemoteControlEnabled(deviceId: String): Boolean
    suspend fun getHandshakeDelayMs(deviceId: String): Long
    suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean)
    suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean)
    suspend fun setRemoteControlEnabled(deviceId: String, enabled: Boolean)
    suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long)
    suspend fun getDeviceName(deviceId: String): String?

    /** Persists a name a person chose, so it is never replaced by a hardware name. */
    suspend fun setDeviceName(deviceId: String, name: String)

    /** Fujifilm: set the camera's date, time and time zone on connect (default on). */
    suspend fun isTimeSyncEnabled(deviceId: String): Boolean = true
    suspend fun setTimeSyncEnabled(deviceId: String, enabled: Boolean) = Unit

    /** Fujifilm: seconds between the camera's location requests while it is on. */
    suspend fun getLocationIntervalS(deviceId: String): Int = FujifilmBluetoothConstants.GEOTAG_SYNC_INTERVAL_SECONDS
    suspend fun setLocationIntervalS(deviceId: String, seconds: Int) = Unit

    /** Fujifilm: seconds between the phone's location fixes while the camera is in standby. */
    suspend fun getStandbyIntervalS(deviceId: String): Int = FujifilmBluetoothConstants.STANDBY_INTERVAL_SECONDS
    suspend fun setStandbyIntervalS(deviceId: String, seconds: Int) = Unit

    /** Sony: seconds between the location updates the phone sends while the camera is on. */
    suspend fun getSendIntervalS(deviceId: String): Int = SonyBluetoothConstants.SEND_INTERVAL_SECONDS
    suspend fun setSendIntervalS(deviceId: String, seconds: Int) = Unit

    /** Sony: keep the camera out of power save while it is connected (default off). */
    suspend fun isKeepAwakeEnabled(deviceId: String): Boolean = false
    suspend fun setKeepAwakeEnabled(deviceId: String, enabled: Boolean) = Unit
}

interface DeviceDetailServiceActions {
    fun startAlwaysOn(deviceAddress: String)
    fun requestShutdown(deviceAddress: String)
    fun setRemoteControlMonitoring(deviceAddress: String, enabled: Boolean)
}

data class DeviceDetailToggleState(
    val buttonEnabled: Boolean = true,
    val deviceName: String = "",
    val isDeviceEnabled: Boolean = true,
    val isAlwaysOnEnabled: Boolean = false,
    val isRemoteControlEnabled: Boolean = false,
    val handshakeDelayMs: Long = 0,
    val isTimeSyncEnabled: Boolean = true,
    val locationIntervalS: Int = FujifilmBluetoothConstants.GEOTAG_SYNC_INTERVAL_SECONDS,
    val standbyIntervalS: Int = FujifilmBluetoothConstants.STANDBY_INTERVAL_SECONDS,
    val sendIntervalS: Int = SonyBluetoothConstants.SEND_INTERVAL_SECONDS,
    val isKeepAwakeEnabled: Boolean = false,
)

class DeviceDetailStateStore(
    private val dataSource: DeviceDetailDataSource,
) {
    private val _uiState = MutableStateFlow(DeviceDetailToggleState())
    val uiState: StateFlow<DeviceDetailToggleState> = _uiState.asStateFlow()

    suspend fun load(deviceId: String, deviceName: String? = null) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        _uiState.update {
            it.copy(
                deviceName = dataSource.getDeviceName(normalized) ?: deviceName.orEmpty(),
                isAlwaysOnEnabled = dataSource.isAlwaysOnEnabled(normalized),
                isDeviceEnabled = dataSource.isDeviceEnabled(normalized),
                isRemoteControlEnabled = dataSource.isRemoteControlEnabled(normalized),
                handshakeDelayMs = dataSource.getHandshakeDelayMs(normalized),
                isTimeSyncEnabled = dataSource.isTimeSyncEnabled(normalized),
                locationIntervalS = dataSource.getLocationIntervalS(normalized),
                standbyIntervalS = dataSource.getStandbyIntervalS(normalized),
                sendIntervalS = dataSource.getSendIntervalS(normalized),
                isKeepAwakeEnabled = dataSource.isKeepAwakeEnabled(normalized),
            )
        }
    }

    suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean, deviceName: String? = null) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        dataSource.setDeviceEnabled(normalized, enabled)
        _uiState.update { it.copy(isDeviceEnabled = enabled) }
    }

    suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean, deviceName: String? = null) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        dataSource.setAlwaysOnEnabled(normalized, enabled)
        _uiState.update { it.copy(isAlwaysOnEnabled = enabled) }
    }

    suspend fun setRemoteControlEnabled(
        deviceId: String,
        enabled: Boolean,
        deviceName: String? = null
    ) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        dataSource.setRemoteControlEnabled(normalized, enabled)
        _uiState.update { it.copy(isRemoteControlEnabled = enabled) }
    }

    suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long, deviceName: String? = null) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        dataSource.setHandshakeDelayMs(normalized, delayMs)
        _uiState.update { it.copy(handshakeDelayMs = delayMs) }
    }

    suspend fun setTimeSyncEnabled(deviceId: String, enabled: Boolean, deviceName: String? = null) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        dataSource.setTimeSyncEnabled(normalized, enabled)
        _uiState.update { it.copy(isTimeSyncEnabled = enabled) }
    }

    suspend fun setLocationIntervalS(deviceId: String, seconds: Int) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized)
        dataSource.setLocationIntervalS(normalized, seconds)
        _uiState.update { it.copy(locationIntervalS = seconds) }
    }

    suspend fun setStandbyIntervalS(deviceId: String, seconds: Int) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized)
        dataSource.setStandbyIntervalS(normalized, seconds)
        _uiState.update { it.copy(standbyIntervalS = seconds) }
    }

    suspend fun setSendIntervalS(deviceId: String, seconds: Int) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized)
        dataSource.setSendIntervalS(normalized, seconds)
        _uiState.update { it.copy(sendIntervalS = seconds) }
    }

    suspend fun setKeepAwakeEnabled(deviceId: String, enabled: Boolean) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized)
        dataSource.setKeepAwakeEnabled(normalized, enabled)
        _uiState.update { it.copy(isKeepAwakeEnabled = enabled) }
    }

    suspend fun setDeviceName(deviceId: String, name: String) {
        val normalized = deviceId.uppercase()
        val trimmed = name.trim()
        // An empty rename is a no-op rather than a way to erase a camera's name.
        if (trimmed.isEmpty()) return
        dataSource.ensureDeviceExists(normalized, trimmed)
        dataSource.setDeviceName(normalized, trimmed)
        _uiState.update { it.copy(deviceName = trimmed) }
    }

    fun setButtonEnabled(enabled: Boolean) {
        _uiState.update { it.copy(buttonEnabled = enabled) }
    }
}
