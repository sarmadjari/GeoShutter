package com.sasch.cameragps.sharednew.status

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraProtocol
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSettingState
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.ui.devicelist.CameraBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoShutterStatusTest {

    private val sony = SavedCamera("aa:01", "ILCE-1M2", CameraBrand.Sony)
    private val fuji = SavedCamera("BB:02", "X100VI", CameraBrand.Fujifilm)

    @Test
    fun namesFollowTheCameraList() {
        val status = geoShutterStatus(
            enabled = true,
            savedCameras = listOf(sony, fuji, SavedCamera("CC:03", "ILCE-7M4", null)),
            devices = listOf(
                CameraDevice(mac = "AA:01", deviceName = "ILCE-1M2"),
                CameraDevice(mac = "BB:02", deviceName = "X100VI-1A2B"),
                CameraDevice(mac = "CC:03", deviceName = "N/A"),
            ),
            sessions = emptyMap(),
            transmitting = false,
        )
        assertEquals(
            listOf(
                Triple("AA:01", "ILCE-1M2", "Sony α1 II"),
                Triple("BB:02", "X100VI-1A2B", "Fujifilm X100VI"),
                Triple("CC:03", "ILCE-7M4", "Sony α7 IV"),
            ),
            status.cameras.map { Triple(it.id, it.name, it.model) },
        )
    }

    @Test
    fun stateDistinguishesAwayConnectingAndSending() {
        fun session(id: String, phase: BleSessionPhase) = CameraSession(id, phase)
        val sessions = mapOf(
            "AA:01" to session("AA:01", BleSessionPhase.Transmitting),
            "BB:02" to session("BB:02", BleSessionPhase.EnablingGps),
            "CC:03" to session("CC:03", BleSessionPhase.Connecting),
        )
        val cameras = listOf(sony, fuji, SavedCamera("CC:03", "ILCE-7M4", null))

        val sending = geoShutterStatus(true, cameras, emptyList(), sessions, transmitting = true)
        assertEquals(
            listOf(CameraState.Sending, CameraState.Connecting, CameraState.Away),
            sending.cameras.map { it.state },
        )
        assertEquals(listOf("AA:01"), sending.sending.map { it.id })

        // Location updates not running yet: a ready camera is still connecting.
        val noFix = geoShutterStatus(true, cameras, emptyList(), sessions, transmitting = false)
        assertEquals(CameraState.Connecting, noFix.cameras.first().state)
    }

    @Test
    fun aFujifilmSessionNamesTheBrandWhenTheAdvertisementDidNot() {
        val status = geoShutterStatus(
            enabled = true,
            savedCameras = listOf(SavedCamera("BB:02", "X100VI", brand = null)),
            devices = emptyList(),
            sessions = mapOf(
                "BB:02" to CameraSession(
                    "BB:02",
                    BleSessionPhase.Transmitting,
                    protocol = CameraProtocol.FujifilmSecure,
                ),
            ),
            transmitting = true,
        )
        assertEquals("X100VI", status.cameras.single().name)
        assertEquals("Fujifilm X100VI", status.cameras.single().model)
    }

    @Test
    fun aFujifilmCameraWithLocationSyncOffShowsThat() {
        fun state(locationSync: Boolean?) = geoShutterStatus(
            enabled = true,
            savedCameras = listOf(fuji),
            devices = emptyList(),
            sessions = mapOf(
                "BB:02" to CameraSession(
                    "BB:02",
                    BleSessionPhase.Transmitting,
                    protocol = CameraProtocol.FujifilmSecure,
                    fujifilmLocationSync = CameraSettingState(supported = true, enabled = locationSync),
                    cameraResponding = true,
                ),
            ),
            transmitting = false,
        )

        val off = state(locationSync = false)
        assertEquals(CameraState.LocationSyncOff, off.cameras.single().state)
        assertEquals(listOf("BB:02"), off.locationSyncOff.map { it.id })
        assertTrue(off.sending.isEmpty() && off.connecting.isEmpty())
        // On or not read yet: waiting for the location as usual.
        assertEquals(CameraState.Connecting, state(locationSync = true).cameras.single().state)
        assertEquals(CameraState.Connecting, state(locationSync = null).cameras.single().state)
    }

    @Test
    fun aFujifilmCameraSwitchedOffInStandbyShowsThat() {
        val status = geoShutterStatus(
            enabled = true,
            savedCameras = listOf(fuji),
            devices = emptyList(),
            sessions = mapOf(
                "BB:02" to CameraSession(
                    "BB:02",
                    BleSessionPhase.Transmitting,
                    protocol = CameraProtocol.FujifilmSecure,
                    cameraResponding = true,
                    inStandby = true,
                ),
            ),
            transmitting = true,
        )

        assertEquals(CameraState.Standby, status.cameras.single().state)
        assertEquals(listOf("BB:02"), status.standby.map { it.id })
        assertTrue(status.sending.isEmpty())
    }

    @Test
    fun aSilentFujifilmCameraIsStillConnecting() {
        fun status(responding: Boolean) = geoShutterStatus(
            enabled = true,
            savedCameras = listOf(fuji),
            devices = emptyList(),
            sessions = mapOf(
                "BB:02" to CameraSession(
                    "BB:02",
                    BleSessionPhase.Transmitting,
                    protocol = CameraProtocol.FujifilmSecure,
                    cameraResponding = responding,
                ),
            ),
            transmitting = true,
        ).cameras.single().state

        assertEquals(CameraState.Connecting, status(responding = false))
        assertEquals(CameraState.Sending, status(responding = true))
    }
}
