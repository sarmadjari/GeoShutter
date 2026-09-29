package com.sasch.cameragps.sharednew.bluetooth.session

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Bluetooth protocol a camera speaks, detected after service discovery. */
enum class CameraProtocol {
    /** Sony: location is pushed every few seconds. Also used for unknown cameras. */
    Sony,

    /** Fujifilm, secure protocol: location is sent when the camera asks for it. */
    FujifilmSecure,
}

/**
 * State of one camera session. Replaces the Android `CameraConnectionConfig`
 * state fields and the iOS `PeripheralSession`/`PeripheralPhase` pair.
 */
data class CameraSession(
    /** Uppercased MAC address (Android) / peripheral UUID string (iOS). */
    val identifier: String,
    val phase: BleSessionPhase = BleSessionPhase.Connecting,
    val protocol: CameraProtocol = CameraProtocol.Sony,
    val remoteFeatureActive: Boolean = false,
    /** A shutter sequence is currently running (UI feedback on the shutter button). */
    val shutterSequenceActive: Boolean = false,
    /** Consecutive auth-error retries (iOS pairing); reset on any success. */
    val pairingRetryCount: Int = 0,
    /** One-shot retry guard for the config read (intermittent GATT 133 on Android). */
    val hasRetriedConfigRead: Boolean = false,
    /** Camera-reported status for the UI only; some cameras may report disabled during startup. */
    val locationDisabledByCamera: Boolean = false,
    val autoTimeCorrection: CameraSettingState = CameraSettingState(),
    val autoAreaAdjustment: CameraSettingState = CameraSettingState(),
    val fujifilmLocationSync: CameraSettingState = CameraSettingState(),
    val fujifilmConnectWhileOff: CameraSettingState = CameraSettingState(),
    /**
     * Fujifilm: the camera sent a notification since it connected. A connected camera
     * can stay silent, ignoring the phone, until it is reconnected.
     */
    val cameraResponding: Boolean = false,
    /**
     * Fujifilm: the camera is switched off or asleep but stays connected in standby
     * (CONNECT WHILE POWER OFF). It still asks for locations and keeps the last one for
     * its next photo.
     */
    val inStandby: Boolean = false,
    /** Fujifilm: seconds between the camera's location requests while it is awake. */
    val locationIntervalS: Int = FujifilmBluetoothConstants.GEOTAG_SYNC_INTERVAL_SECONDS,
    /** Fujifilm: seconds between the phone's location fixes while the camera is in standby. */
    val standbyIntervalS: Int = FujifilmBluetoothConstants.STANDBY_INTERVAL_SECONDS,
) {
    fun autoCorrectionSetting(setting: CameraAutoCorrectionSetting): CameraSettingState =
        when (setting) {
            CameraAutoCorrectionSetting.Time -> autoTimeCorrection
            CameraAutoCorrectionSetting.Area -> autoAreaAdjustment
            CameraAutoCorrectionSetting.FujifilmLocationSync -> fujifilmLocationSync
            CameraAutoCorrectionSetting.FujifilmConnectWhileOff -> fujifilmConnectWhileOff
        }

    val isLocationReady: Boolean
        get() = phase == BleSessionPhase.Transmitting

    /** False for a Fujifilm camera whose location sync is off: it asks for no location. */
    val wantsLocation: Boolean
        get() = protocol != CameraProtocol.FujifilmSecure || fujifilmLocationSync.enabled != false

    /**
     * Ready and taking locations. A Fujifilm camera only asks once it responds (see
     * [cameraResponding]) and while its location sync is on.
     */
    val takesLocation: Boolean
        get() = isLocationReady && wantsLocation &&
                (protocol != CameraProtocol.FujifilmSecure || cameraResponding)

    /**
     * How often this camera needs a fresh fix from the phone: Sony every few seconds, a
     * Fujifilm camera as often as it asks, or its standby interval while in standby.
     */
    val locationUpdateIntervalMs: Long
        get() = when {
            protocol != CameraProtocol.FujifilmSecure -> SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS
            inStandby -> standbyIntervalS * 1000L
            else -> locationIntervalS * 1000L
        }
}

/**
 * Per-device session registry, exposed as a StateFlow for both platform UIs.
 * All mutations happen on the orchestrator's confined dispatcher.
 */
class CameraSessionRegistry {

    private val _sessions = MutableStateFlow<Map<String, CameraSession>>(emptyMap())
    val sessions: StateFlow<Map<String, CameraSession>> = _sessions

    /** Create-or-update. Only connection setup should create sessions. */
    fun upsert(identifier: String, transform: (CameraSession) -> CameraSession = { it }) {
        val id = identifier.uppercase()
        val current = _sessions.value[id] ?: CameraSession(id)
        _sessions.value = _sessions.value + (id to transform(current))
    }

    /** Update only if a session exists — phase/remote updates must not resurrect removed sessions. */
    fun updateIfPresent(identifier: String, transform: (CameraSession) -> CameraSession) {
        val id = identifier.uppercase()
        val existing = _sessions.value[id] ?: return
        _sessions.value = _sessions.value + (id to transform(existing))
    }

    fun remove(identifier: String) {
        _sessions.value = _sessions.value - identifier.uppercase()
    }

    fun clear() {
        _sessions.value = emptyMap()
    }

    fun get(identifier: String): CameraSession? = _sessions.value[identifier.uppercase()]

    /** Devices whose handshake completed and are receiving location packets. */
    fun readyIdentifiers(): Set<String> =
        _sessions.value.filterValues { it.isLocationReady }.keys

    /** Number of devices with a live connection (any phase past connecting, minus errors). */
    fun activeCount(): Int = _sessions.value.values.count { it.phase.isActiveConnection() }

    private fun BleSessionPhase.isActiveConnection(): Boolean = when (this) {
        BleSessionPhase.Disconnected,
        BleSessionPhase.Connecting,
        BleSessionPhase.Error,
            -> false

        else -> true
    }
}
