package com.sasch.cameragps.sharednew.bluetooth.session

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A setting stored on the camera that the app reads and changes over Bluetooth. */
enum class CameraAutoCorrectionSetting(
    val characteristicUuid: String,
    /** Service to look the characteristic up in; null for any. */
    val serviceUuid: String? = null,
    /** Value width in bytes: one for Sony, a little-endian uint16 for Fujifilm. */
    val valueSize: Int = 1,
    val protocol: CameraProtocol = CameraProtocol.Sony,
) {
    Time(SonyBluetoothConstants.AUTO_TIME_CORRECTION_UUID),
    Area(SonyBluetoothConstants.AUTO_AREA_ADJUSTMENT_UUID),

    /** Fujifilm's SMARTPHONE LOCATION SYNC. menu setting. */
    FujifilmLocationSync(
        FujifilmBluetoothConstants.LOCATION_SYNC_SETTING_UUID,
        FujifilmBluetoothConstants.NOTIFICATION_SERVICE_UUID,
        valueSize = 2,
        protocol = CameraProtocol.FujifilmSecure,
    ),

    /**
     * Fujifilm's CONNECT WHILE POWER OFF menu setting. Fujifilm's app writes one byte; the
     * X100VI reads as two (`01 00`), so it is read and written as a uint16.
     */
    FujifilmConnectWhileOff(
        FujifilmBluetoothConstants.CONNECT_WHILE_OFF_UUID,
        valueSize = 2,
        protocol = CameraProtocol.FujifilmSecure,
    );

    fun encode(enabled: Boolean): ByteArray =
        ByteArray(valueSize).also { it[0] = if (enabled) 1 else 0 }

    /** Off or on; null for anything else, including an unexpected length. */
    fun decode(value: ByteArray?): Boolean? {
        if (value == null || value.size != valueSize) return null
        var number = 0
        for (i in value.indices.reversed()) number = (number shl 8) or (value[i].toInt() and 0xFF)
        return when (number) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    companion object {
        fun fromUuid(uuid: String): CameraAutoCorrectionSetting? =
            entries.firstOrNull { it.characteristicUuid.equals(uuid, ignoreCase = true) }

        fun forProtocol(protocol: CameraProtocol): List<CameraAutoCorrectionSetting> =
            entries.filter { it.protocol == protocol }
    }
}

/** A camera-owned setting. Null means unknown, never an assumed off value. */
data class CameraSettingState(
    /** True after reading a valid value from a characteristic supporting writes with a response. */
    val supported: Boolean? = null,
    val enabled: Boolean? = null,
    val pending: Boolean = false,
    val failed: Boolean = false,
)

interface CameraAutoCorrectionControls {
    val sessions: StateFlow<Map<String, CameraSession>>
    fun refreshAutoCorrectionSettings(identifier: String)
    fun setAutoCorrectionSetting(
        identifier: String,
        setting: CameraAutoCorrectionSetting,
        enabled: Boolean
    )

    /** The camera's location intervals changed in the database; use them now. */
    fun applyLocationIntervals(identifier: String) = Unit

    /** Sony: "Keep the camera awake" changed in the database; apply it now. */
    fun applyKeepAwake(identifier: String) = Unit

    /** Sony cameras that GeoShutter keeps waking up ([SonyWakeLoopDetector]). */
    val wakeLoops: StateFlow<Set<String>> get() = noWakeLoops
}

private val noWakeLoops: StateFlow<Set<String>> = MutableStateFlow(emptySet())
