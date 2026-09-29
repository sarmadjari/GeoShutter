package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.ui.devicelist.CameraBrand

/**
 * Shell-owned device identity/connection state. Per-device session state
 * (phase, remote feature, shutter) lives in `CameraSession` — observe the
 * sessions StateFlow instead of mirroring fields here.
 */
data class BluetoothDeviceInfo(
    val identifier: String,
    val name: String,
    val isConnected: Boolean,
    val isSaved: Boolean = false,
    /** Android CDM bond state; iOS always true (pairing is part of connecting). */
    val isPaired: Boolean = true,
    /** Brand and model shown under [name] when they differ, e.g. "Fujifilm X100VI" under "X100VI-1A2B". */
    val model: String? = null,
    /** The brand seen when the camera was added (iOS: its picker item); null when unknown. */
    val brand: CameraBrand? = null,
)

enum class BluetoothCapability {
    Scan,
    Connect,
    ObserveConnection,
}

