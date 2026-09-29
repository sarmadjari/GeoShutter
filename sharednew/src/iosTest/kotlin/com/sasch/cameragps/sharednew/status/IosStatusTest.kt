package com.sasch.cameragps.sharednew.status

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import kotlin.test.Test
import kotlin.test.assertEquals

class IosStatusTest {
    private fun device(id: String, saved: Boolean = true) =
        BluetoothDeviceInfo(identifier = id, name = "Camera $id", isConnected = false, isSaved = saved)

    /**
     * The widget can't follow the seconds a connection takes to set up, and the app's camera
     * list shows such a camera as not connected: a Sony camera switched off but still
     * connected was left amber on the widget while the app showed it red.
     */
    @Test
    fun aCameraBeingSetUpShowsAsNotConnected() {
        val status = iosStatus(
            enabled = true,
            devices = listOf(device("A"), device("B"), device("C")),
            sessions = mapOf(
                "A" to CameraSession("A", BleSessionPhase.EnablingGps),
                "B" to CameraSession("B", BleSessionPhase.Transmitting),
                "C" to CameraSession("C", BleSessionPhase.EnablingGps, cameraOff = true),
            ),
            transmitting = true,
        )
        assertEquals(
            listOf(CameraState.Away, CameraState.Sending, CameraState.Away),
            status.cameras.map { it.state },
        )
        assertEquals(StatusHeadline.Sending, status.headline())
    }

    @Test
    fun onlySavedCamerasAreListed() {
        val status = iosStatus(true, listOf(device("A"), device("B", saved = false)), emptyMap(), false)
        assertEquals(listOf("A"), status.cameras.map { it.id })
        assertEquals(StatusHeadline.Waiting, status.headline())
    }
}
