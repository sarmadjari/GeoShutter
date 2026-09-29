package com.sasch.cameragps.sharednew.status

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraProtocol
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.ui.devicelist.CameraBrand
import com.sasch.cameragps.sharednew.ui.devicelist.cameraModelLine

/**
 * What GeoShutter is doing, shown outside the app: the status notification, the
 * Quick Settings tile and the home-screen widget.
 */
data class GeoShutterStatus(
    /** The app's "Enable App" switch. */
    val enabled: Boolean,
    /** Saved cameras, in the order of the camera list. */
    val cameras: List<CameraStatus> = emptyList(),
) {
    val sending: List<CameraStatus> get() = cameras.filter { it.state == CameraState.Sending }
    val connecting: List<CameraStatus>
        get() = cameras.filter { it.state == CameraState.Connecting }
    val locationSyncOff: List<CameraStatus>
        get() = cameras.filter { it.state == CameraState.LocationSyncOff }
    val cameraOff: List<CameraStatus>
        get() = cameras.filter { it.state == CameraState.CameraOff }
}

data class CameraStatus(
    val id: String,
    val name: String,
    /** Brand and model, e.g. "Sony α1 II"; null when unknown or the same as [name]. */
    val model: String?,
    val state: CameraState,
)

enum class CameraState {
    /** Not connected (also while the app is still looking for it). */
    Away,

    /** Connected and setting up, or waiting for the first location. */
    Connecting,

    /** Fujifilm: connected, but the camera's location sync is off, so it takes no location. */
    LocationSyncOff,

    /**
     * Fujifilm: switched off but connected in standby (CONNECT WHILE POWER OFF); it still
     * receives the location, so its next photo is tagged right away.
     */
    CameraOff,

    /** Receiving the phone's location. */
    Sending,
}

/** A camera the user added: its pairing name and, when known, its brand. */
data class SavedCamera(val id: String, val pairingName: String, val brand: CameraBrand?)

/** Phases with a live link; `Connecting` only means a connection was requested. */
private val LINKED_PHASES = setOf(
    BleSessionPhase.Connected,
    BleSessionPhase.DiscoveringServices,
    BleSessionPhase.ReadingConfig,
    BleSessionPhase.EnablingGps,
    BleSessionPhase.LockingGps,
    BleSessionPhase.SyncingTime,
    BleSessionPhase.RemoteStatusProbing,
    BleSessionPhase.Transmitting,
)

/**
 * Builds the status with the camera list's naming: the stored name (chosen by the user
 * or reported by the camera), else the pairing name; brand and model underneath.
 * [transmitting] is whether location updates are running.
 */
fun geoShutterStatus(
    enabled: Boolean,
    savedCameras: List<SavedCamera>,
    devices: List<CameraDevice>,
    sessions: Map<String, CameraSession>,
    transmitting: Boolean,
): GeoShutterStatus {
    val devicesById = devices.associateBy { it.mac.uppercase() }
    val cameras = savedCameras.map { saved ->
        val id = saved.id.uppercase()
        val session = sessions[id]
        val name = devicesById[id]?.deviceName?.takeUnless { it.isBlank() || it == "N/A" }
            ?: saved.pairingName
        val brand = saved.brand
            ?: CameraBrand.Fujifilm.takeIf { session?.protocol == CameraProtocol.FujifilmSecure }
        CameraStatus(
            id = id,
            name = name,
            model = cameraModelLine(brand, saved.pairingName)?.takeIf { it != name },
            state = when {
                session == null -> CameraState.Away
                session.isLocationReady && !session.wantsLocation -> CameraState.LocationSyncOff
                session.isLocationReady && session.cameraOff -> CameraState.CameraOff
                session.takesLocation && transmitting -> CameraState.Sending
                session.phase in LINKED_PHASES -> CameraState.Connecting
                else -> CameraState.Away
            },
        )
    }
    return GeoShutterStatus(enabled, cameras)
}
