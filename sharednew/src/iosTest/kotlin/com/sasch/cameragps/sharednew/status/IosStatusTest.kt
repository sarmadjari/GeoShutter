package com.sasch.cameragps.sharednew.status

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
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

    private fun status(sony: CameraState, fujifilm: CameraState = CameraState.Standby, enabled: Boolean = true) =
        GeoShutterStatus(
            enabled = enabled,
            cameras = listOf(
                CameraStatus("F", "X100VI-568C", "Fujifilm X100VI", fujifilm),
                CameraStatus("S", "ILCE-1M2", "Sony α1 II", sony),
            ),
        )

    @Test
    fun aCameraThatDropsWaitsForTheGracePeriod() {
        val sending = status(CameraState.Sending)
        assertEquals(CAMERA_DROP_GRACE_MS, publishDelayMs(status(CameraState.Away), published = sending))
        assertEquals(
            CAMERA_DROP_GRACE_MS,
            publishDelayMs(status(CameraState.Sending, fujifilm = CameraState.Away), published = sending),
        )
    }

    @Test
    fun otherChangesWaitForTheDebounce() {
        val away = status(CameraState.Away)
        assertEquals(STATUS_DEBOUNCE_MS, publishDelayMs(away, published = null))
        assertEquals(STATUS_DEBOUNCE_MS, publishDelayMs(status(CameraState.Sending), published = away))
        assertEquals(
            STATUS_DEBOUNCE_MS,
            publishDelayMs(status(CameraState.Sending, fujifilm = CameraState.Sending), status(CameraState.Sending)),
        )
        // Switched off in the app: written right away by publishNow, and by the flow soon after.
        assertEquals(
            STATUS_DEBOUNCE_MS,
            publishDelayMs(status(CameraState.Away, enabled = false), published = status(CameraState.Sending)),
        )
    }

    /**
     * The α1 II on the iPhone, 2026-09-29: its power save ended the connection about every
     * minute and the phone had it back 6 to 12 s later. iOS reloads the widget at most every
     * 5 minutes, and one reload during a gap showed the camera as away for 5 minutes.
     */
    @Test
    fun aSonyPowerSaveGapWritesNothing() = runTest {
        val sending = status(CameraState.Sending)
        val away = status(CameraState.Away)
        var published: GeoShutterStatus? = null
        val written = mutableListOf<Pair<Long, CameraState>>()
        flow {
            emit(sending)
            repeat(3) {
                delay(58_000)
                emit(away)
                delay(12_000)
                emit(sending)
            }
            delay(60_000)
            emit(away) // switched off for good
            delay(60_000)
        }.settled { published }.collect {
            if (it != published) written += currentTime to it.cameras.last().state
            published = it
        }
        assertEquals(
            listOf(2_000L to CameraState.Sending, 300_000L to CameraState.Away),
            written,
        )
    }

    @Test
    fun aCameraSwitchedOnIsWrittenAfterTheDebounce() = runTest {
        var published: GeoShutterStatus? = null
        val written = mutableListOf<Pair<Long, CameraState>>()
        flow {
            emit(status(CameraState.Away))
            delay(10_000)
            emit(status(CameraState.Sending))
            delay(10_000)
        }.settled { published }.collect {
            written += currentTime to it.cameras.last().state
            published = it
        }
        assertEquals(listOf(2_000L to CameraState.Away, 12_000L to CameraState.Sending), written)
    }
}
