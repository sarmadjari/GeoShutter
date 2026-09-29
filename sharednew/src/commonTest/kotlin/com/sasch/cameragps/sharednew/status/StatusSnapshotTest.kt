package com.sasch.cameragps.sharednew.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusSnapshotTest {

    private val texts = StatusTexts(
        off = "Off",
        sending = "Sending location",
        connecting = "Connecting",
        standby = "Standby",
        locationSyncOff = "Location sync off",
        waiting = "Waiting",
        noCameras = "No cameras added yet",
    )

    private fun camera(id: String, state: CameraState, model: String? = null) =
        CameraStatus(id, "Camera $id", model, state)

    @Test
    fun theHeadlineFollowsTheMostImportantCamera() {
        fun headline(enabled: Boolean, vararg states: CameraState) =
            GeoShutterStatus(enabled, states.mapIndexed { i, state -> camera("$i", state) }).headline()

        assertEquals(StatusHeadline.Off, headline(false, CameraState.Sending))
        assertEquals(StatusHeadline.Sending, headline(true, CameraState.Connecting, CameraState.Sending))
        assertEquals(StatusHeadline.Connecting, headline(true, CameraState.Standby, CameraState.Connecting))
        assertEquals(StatusHeadline.Standby, headline(true, CameraState.LocationSyncOff, CameraState.Standby))
        assertEquals(StatusHeadline.LocationSyncOff, headline(true, CameraState.Away, CameraState.LocationSyncOff))
        assertEquals(StatusHeadline.Waiting, headline(true, CameraState.Away))
        assertEquals(StatusHeadline.Waiting, headline(true))
    }

    @Test
    fun camerasCarryTheirStateAndANoteForStandbyAndSyncOff() {
        val snapshot = statusSnapshot(
            GeoShutterStatus(
                enabled = true,
                cameras = listOf(
                    camera("A", CameraState.Sending, "Sony α1 II"),
                    camera("B", CameraState.Standby, "Fujifilm X100VI"),
                    camera("C", CameraState.LocationSyncOff),
                    camera("D", CameraState.Away),
                ),
            ),
            texts,
        )
        assertTrue(snapshot.sending)
        assertEquals("Sending location", snapshot.headline)
        assertEquals(
            listOf("sending" to null, "standby" to "Standby", "syncOff" to "Location sync off", "away" to null),
            snapshot.cameras.map { it.state to it.note },
        )
    }

    @Test
    fun whileOffEveryCameraIsGreyWithoutNotes() {
        val snapshot = statusSnapshot(
            GeoShutterStatus(false, listOf(camera("A", CameraState.Sending), camera("B", CameraState.Standby))),
            texts,
        )
        assertFalse(snapshot.sending)
        assertEquals("Off", snapshot.headline)
        assertEquals(listOf("off" to null, "off" to null), snapshot.cameras.map { it.state to it.note })
    }

    @Test
    fun jsonEscapesTextsAndWritesNulls() {
        val json = StatusSnapshot(
            enabled = true,
            sending = false,
            headline = "Waiting",
            emptyText = "Say \"hi\"\\\n",
            cameras = listOf(StatusSnapshot.Camera("ID", "α1 II", null, "away", null)),
        ).toJson()
        assertEquals(
            "{\"enabled\":true,\"sending\":false,\"headline\":\"Waiting\"," +
                "\"emptyText\":\"Say \\\"hi\\\"\\\\\\n\",\"cameras\":[{\"id\":\"ID\",\"name\":\"α1 II\"," +
                "\"model\":null,\"state\":\"away\",\"note\":null}],\"liveActivity\":false}",
            json,
        )
    }

    @Test
    fun theLiveActivitySwitchTravelsWithTheStatus() {
        val status = GeoShutterStatus(true, listOf(camera("A", CameraState.Sending)))
        assertFalse(statusSnapshot(status, texts).liveActivity)
        val snapshot = statusSnapshot(status, texts, liveActivity = true)
        assertTrue(snapshot.liveActivity)
        assertTrue(snapshot.toJson().endsWith("],\"liveActivity\":true}"))
    }
}
