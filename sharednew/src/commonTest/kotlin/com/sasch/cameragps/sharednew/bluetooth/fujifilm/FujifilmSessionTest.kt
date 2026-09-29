/*
 * Author: Sarmad Jari
 *
 * Checks the Fujifilm session against furble's secure handshake order
 * (https://github.com/gkoh/furble, lib/furble/FujifilmSecure.cpp, MIT License)
 * and that Sony cameras keep their push-based delivery alongside it.
 */
package com.sasch.cameragps.sharednew.bluetooth.fujifilm

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.PlatformLogger
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.coordinator.PlatformTimeZoneInfo
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import com.sasch.cameragps.sharednew.bluetooth.session.CameraAutoCorrectionSetting
import com.sasch.cameragps.sharednew.bluetooth.session.CameraProtocol
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSettingState
import com.sasch.cameragps.sharednew.bluetooth.session.OrchestratorEvent
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants as Sony
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants as Fuji

@OptIn(ExperimentalCoroutinesApi::class)
class FujifilmSessionTest {

    @BeforeTest
    fun disablePlatformLogging() {
        KmLogging.setLoggers()
    }

    @AfterTest
    fun restorePlatformLogging() {
        KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true)))
    }

    @Test
    fun handshakeFollowsFurblesOrderAndMarksTheCameraReady() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()

        val steps = f.transport.steps("F")
        // Its value depends on the current time; see setupSetsTheCameraClock….
        val timeWrite = steps.single { it is Step.Write && it.characteristicUuid == Fuji.UTC_TIME_ZONE_UUID }
        val expected: List<Step> = listOf(
            Step.Read(Fuji.STATUS_CHARACTERISTIC_UUID, Fuji.PAIR_SERVICE_UUID),
            Step.Write(Fuji.STATUS_CHARACTERISTIC_UUID, Fuji.PAIR_SERVICE_UUID, listOf(0x07, 0x96, 0x00, 0x20)),
            Step.Write(
                Fuji.IDENTIFIER_CHARACTERISTIC_UUID,
                Fuji.PAIR_SERVICE_UUID,
                "GeoShutter".encodeToByteArray().map { it.toInt() and 0xFF },
            ),
        ) + (Fuji.REQUIRED_SUBSCRIPTIONS + Fuji.OPTIONAL_SUBSCRIPTIONS).map {
            Step.Subscribe(it.characteristicUuid, it.serviceUuid, it.indication)
        } + Step.Write(
            Fuji.GEOTAG_SYNC_INTERVAL_UUID,
            Fuji.NOTIFICATION_SERVICE_UUID,
            listOf(0x0A, 0x00),
        ) + timeWrite + // like Fujifilm's app, right after the sync interval
                // Before the camera counts as ready: does it want locations, is it on?
                Step.Read(Fuji.LOCATION_SYNC_SETTING_UUID, Fuji.NOTIFICATION_SERVICE_UUID) +
                Step.Read(Fuji.CONNECT_WHILE_OFF_UUID, null) +
                Step.Read(Fuji.POWER_SWITCH_UUID, null) +
                Step.Read(Fuji.NOTIFICATION_4_UUID, Fuji.NOTIFICATION_SERVICE_UUID) // camera name
        assertEquals(expected, steps)
        // The two indication characteristics come first, as in furble.
        assertEquals(
            listOf(Fuji.INDICATION_1_UUID, Fuji.INDICATION_2_UUID),
            Fuji.REQUIRED_SUBSCRIPTIONS.filter { it.indication }.map { it.characteristicUuid },
        )

        val session = f.session("F")
        assertEquals(CameraProtocol.FujifilmSecure, session.protocol)
        assertTrue(session.isLocationReady)
        assertTrue(f.source.active)
        assertTrue(f.events.any { it is OrchestratorEvent.HandshakeCompleted })
    }

    @Test
    fun locationIsOnlySentWhenTheCameraAsksForIt() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()
        f.fix(latitude = 52.52, longitude = 13.405, altitude = 34.0)
        advanceTimeBy(Sony.LOCATION_UPDATE_INTERVAL_MS * 4)
        runCurrent()
        assertTrue(f.transport.geotagWrites("F").isEmpty())

        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        val packet = f.transport.geotagWrites("F").single()
        assertEquals(FujifilmPacketBuilder.GEOTAG_PACKET_SIZE, packet.size)
        assertEquals((52.52 * 1.0E7).toInt(), packet.int32(0))
        assertEquals((13.405 * 1.0E7).toInt(), packet.int32(4))
        assertEquals(34, packet.int32(8))

        // Other notifications don't trigger a location write.
        f.notify("F", Fuji.NOTIFICATION_1_UUID, byteArrayOf(0x02, 0x00))
        f.notify("F", Fuji.NOTIFICATION_4_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertEquals(1, f.transport.geotagWrites("F").size)

        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertEquals(2, f.transport.geotagWrites("F").size)
        assertTrue(f.transport.sonyLocationWrites("F").isEmpty())
    }

    @Test
    fun aRequestBeforeTheFirstFixIsAnsweredWhenOneArrives() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()

        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertTrue(f.transport.geotagWrites("F").isEmpty())

        f.fix()
        runCurrent()
        assertEquals(1, f.transport.geotagWrites("F").size)

        // Answered once: a later fix alone doesn't send again.
        f.fix()
        runCurrent()
        assertEquals(1, f.transport.geotagWrites("F").size)
    }

    @Test
    fun failedOptionalSubscriptionsAreTolerated() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.failingSubscriptions += Fuji.NOTIFICATION_7_UUID
        f.connect("F", FUJIFILM)
        runCurrent()

        assertTrue(f.session("F").isLocationReady)
    }

    @Test
    fun failedRequiredStepsAbortTheSession() = runTest {
        for (required in listOf(Fuji.NOTIFICATION_5_UUID, Fuji.GEOTAG_SYNC_INTERVAL_UUID)) {
            val f = Fixture(backgroundScope)
            f.transport.failingSubscriptions += required
            f.connect("F", FUJIFILM)
            runCurrent()

            assertEquals(BleSessionPhase.Error, f.session("F").phase, required)
            assertFalse(f.source.active, required)
            assertFalse(
                f.transport.steps("F").any {
                    it is Step.Write && it.characteristicUuid == Fuji.GEOTAG_SYNC_INTERVAL_UUID
                },
                required,
            )
            f.orchestrator.shutdownAll()
        }
    }

    @Test
    fun anUnexpectedStatusValueAbortsBeforeAnythingIsWritten() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.status = byteArrayOf(0x07, 0x96.toByte(), 0x00)
        f.connect("F", FUJIFILM)
        runCurrent()

        assertEquals(BleSessionPhase.Error, f.session("F").phase)
        assertTrue(f.transport.steps("F").none { it is Step.Write })
    }

    @Test
    fun authenticationErrorsAreRetriedAndThenReportedAsRejectedPairing() = runTest {
        val retried = Fixture(backgroundScope)
        retried.transport.authErrorsLeft = 1
        retried.connect("F", FUJIFILM)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(retried.session("F").isLocationReady)

        val rejected = Fixture(backgroundScope)
        rejected.transport.authErrorsLeft = Int.MAX_VALUE
        rejected.connect("F", FUJIFILM)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(BleSessionPhase.Error, rejected.session("F").phase)
        assertTrue(rejected.events.any { it == OrchestratorEvent.PairingFailed("F") })
    }

    @Test
    fun legacyFirmwareIsDetectedButNotSupported() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", setOf(Fuji.LEGACY_PAIR_CHARACTERISTIC_UUID))
        runCurrent()

        assertEquals(BleSessionPhase.Error, f.session("F").phase)
        assertEquals(listOf<Step>(), f.transport.steps("F"))
    }

    @Test
    fun sonyKeepsPushedLocationsWhileFujifilmOnlyGetsAnswers() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("S", SONY)
        f.connect("F", FUJIFILM)
        runCurrent()
        assertEquals(CameraProtocol.Sony, f.session("S").protocol)
        assertTrue(f.session("S").isLocationReady)
        assertTrue(f.session("F").isLocationReady)

        f.fix()
        advanceTimeBy(Sony.LOCATION_UPDATE_INTERVAL_MS * 3 + 1)
        runCurrent()
        assertTrue(f.transport.sonyLocationWrites("S").size >= 3)
        assertTrue(f.transport.geotagWrites("F").isEmpty())
        assertTrue(f.transport.geotagWrites("S").isEmpty())

        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertEquals(1, f.transport.geotagWrites("F").size)
        assertTrue(f.transport.sonyLocationWrites("F").isEmpty())
    }

    @Test
    fun remoteMonitoringIsNeverStartedForAFujifilmCamera() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()
        val handshake = f.transport.steps("F")

        f.orchestrator.setRemoteMonitoring("F", true)
        runCurrent()

        assertEquals(handshake, f.transport.steps("F"))
        assertFalse(f.session("F").remoteFeatureActive)
    }

    @Test
    fun detectionDecidesTheProtocolOnEveryConnect() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("X", FUJIFILM)
        runCurrent()
        assertEquals(CameraProtocol.FujifilmSecure, f.session("X").protocol)

        // Connected again without a disconnect event, now with Sony's GATT table.
        f.connect("X", SONY)
        runCurrent()
        assertEquals(CameraProtocol.Sony, f.session("X").protocol)
        assertTrue(f.session("X").isLocationReady)

        f.fix()
        advanceTimeBy(Sony.LOCATION_UPDATE_INTERVAL_MS + 1)
        runCurrent()
        assertTrue(f.transport.sonyLocationWrites("X").isNotEmpty())
    }

    @Test
    fun aServicesChangeRestartsSonySetupAndIgnoresTheCancelledAttempt() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.holdReads = true
        f.connect("S", SONY)
        runCurrent()
        // The config read is in flight when the camera changes its services;
        // its late response must not drive the restarted handshake.
        val staleRead = f.transport.heldReads.removeAt(0)
        f.transport.emit(BleTransportEvent.ServicesChanged("S"))
        f.transport.emit(staleRead)
        runCurrent()
        assertTrue(f.transport.writes("S", Sony.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND).isEmpty())

        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, f.transport.discoveries("S"))
        f.transport.holdReads = false
        f.transport.releaseReads()
        runCurrent()

        assertEquals(1, f.transport.writes("S", Sony.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND).size)
        assertTrue(f.session("S").isLocationReady)
        assertEquals(CameraProtocol.Sony, f.session("S").protocol)
    }

    @Test
    fun rediscoveryRetriesWhileAndroidIgnoresTheRequest() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("S", SONY)
        runCurrent()
        assertTrue(f.session("S").isLocationReady)

        f.transport.discoveriesToDrop = 2
        f.transport.emit(BleTransportEvent.ServicesChanged("S"))
        runCurrent()
        assertFalse(f.session("S").isLocationReady)

        advanceTimeBy(15_000)
        runCurrent()
        // The first discovery, two ignored ones and the answered one.
        assertEquals(4, f.transport.discoveries("S"))
        assertTrue(f.session("S").isLocationReady)
        assertEquals(2, f.transport.writes("S", Sony.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND).size)
    }

    @Test
    fun aServicesChangeDuringTheFujifilmHandshakeRestartsItCleanly() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.holdReads = true
        f.connect("F", FUJIFILM)
        runCurrent()
        val staleStatusRead = f.transport.heldReads.removeAt(0)
        f.transport.emit(BleTransportEvent.ServicesChanged("F"))
        f.transport.emit(staleStatusRead)
        runCurrent()
        // Setup starts over with a fresh discovery.
        assertEquals(BleSessionPhase.DiscoveringServices, f.session("F").phase)

        advanceTimeBy(1_100)
        runCurrent()
        f.transport.holdReads = false
        f.transport.releaseReads()
        runCurrent()

        assertTrue(f.session("F").isLocationReady)
        assertEquals(CameraProtocol.FujifilmSecure, f.session("F").protocol)
    }

    @Test
    fun theCameraNameFromNot4IsStoredUnlessTheCameraWasRenamed() = runTest {
        val f = Fixture(backgroundScope)
        f.dao.devices["F"] = CameraDevice(mac = "F", deviceName = "X100VI")
        f.dao.devices["G"] =
            CameraDevice(mac = "G", deviceName = "My Fuji", deviceNameIsCustom = true)
        f.dao.devices["S"] = CameraDevice(mac = "S", deviceName = "ILCE-1M2")

        f.connect("F", FUJIFILM)
        f.connect("G", FUJIFILM)
        f.connect("S", SONY)
        runCurrent()

        assertEquals("X100VI-1A2B", f.dao.devices.getValue("F").deviceName)
        assertFalse(f.dao.devices.getValue("F").deviceNameIsCustom)
        assertEquals("My Fuji", f.dao.devices.getValue("G").deviceName)
        // Sony cameras keep their pairing name (unchanged behavior).
        assertEquals("ILCE-1M2", f.dao.devices.getValue("S").deviceName)
        assertTrue(f.session("F").isLocationReady)
    }

    // ---- date, time and time zone ----

    @Test
    fun setupSetsTheCameraClockAndTimeZoneFromThePhone() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()

        val write = f.transport.steps("F").filterIsInstance<Step.Write>()
            .single { it.characteristicUuid == Fuji.UTC_TIME_ZONE_UUID }
        assertEquals(Fuji.TIME_SERVICE_UUID, write.serviceUuid)
        assertEquals(Fuji.TIME_SYNC_PACKET_SIZE, write.value.size)
        val v = write.value
        val sent = LocalDateTime(v[0] or (v[1] shl 8), v[2], v[3], v[4], v[5], v[6])
            .toInstant(TimeZone.UTC)
        assertTrue((Clock.System.now() - sent).absoluteValue < 10.seconds, "UTC date and time")
        // Zone fields: the phone's standard offset and daylight saving flag.
        val zone = PlatformTimeZoneInfo()
        assertEquals(
            FujifilmPacketBuilder.buildTimeSyncPacket(zone.standardOffsetMinutes, zone.dstOffsetMinutes)
                .drop(7).map { it.toInt() and 0xFF },
            v.drop(7),
        )
        assertTrue(f.session("F").isLocationReady)
    }

    @Test
    fun theClockIsLeftAloneWhenTurnedOffForTheCamera() = runTest {
        val f = Fixture(backgroundScope)
        f.dao.timeSyncEnabled = false
        f.connect("F", FUJIFILM)
        runCurrent()
        f.notify("F", Fuji.NOTIFICATION_1_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()

        assertTrue(f.transport.writes("F", Fuji.UTC_TIME_ZONE_UUID).isEmpty())
        assertTrue(f.session("F").isLocationReady)
    }

    @Test
    fun aCameraWithoutTheTimeServiceStillGeotags() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM - Fuji.UTC_TIME_ZONE_UUID)
        runCurrent()

        assertTrue(f.transport.writes("F", Fuji.UTC_TIME_ZONE_UUID).isEmpty())
        assertTrue(f.session("F").isLocationReady)
    }

    @Test
    fun locationRequestsDoNotSendTheTimeAgain() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.holdReads = true
        f.connect("F", FUJIFILM)
        runCurrent()
        // The X100VI notifies right after the subscriptions, before setup ends.
        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        f.transport.holdReads = false
        f.transport.releaseReads()
        runCurrent()
        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()

        assertEquals(1, f.transport.writes("F", Fuji.UTC_TIME_ZONE_UUID).size)
    }

    @Test
    fun aDateSyncRequestAfterSetupIsAnsweredButNotInALoop() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()
        assertEquals(1, f.transport.writes("F", Fuji.UTC_TIME_ZONE_UUID).size)

        f.notify("F", Fuji.NOTIFICATION_1_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        f.notify("F", Fuji.NOTIFICATION_1_UUID, byteArrayOf(0x02, 0x00))
        runCurrent()

        // The first request is answered, the one right after it isn't.
        assertEquals(2, f.transport.writes("F", Fuji.UTC_TIME_ZONE_UUID).size)
    }

    // ---- silent cameras ----

    @Test
    fun aSilentCameraGetsItsSetupRepeatedAndThenAReconnect() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM) // the fake camera never notifies
        runCurrent()
        fun statusReads() = f.transport.steps("F").count {
            it is Step.Read && it.characteristicUuid == Fuji.STATUS_CHARACTERISTIC_UUID
        }
        assertEquals(1, statusReads())

        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(2, statusReads())
        assertEquals(2, f.transport.writes("F", Fuji.UTC_TIME_ZONE_UUID).size)
        assertTrue(f.transport.reconnects.isEmpty())

        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(listOf("F"), f.transport.reconnects)
    }

    @Test
    fun aCameraThatAnswersIsLeftAlone() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()
        assertFalse(f.session("F").cameraResponding)

        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertTrue(f.session("F").cameraResponding)
        advanceTimeBy(40_000)
        runCurrent()

        assertEquals(
            1,
            f.transport.steps("F").count {
                it is Step.Read && it.characteristicUuid == Fuji.STATUS_CHARACTERISTIC_UUID
            },
        )
        assertTrue(f.transport.reconnects.isEmpty())
    }

    // ---- SMARTPHONE LOCATION SYNC. ----

    @Test
    fun readsAndChangesTheCamerasLocationSyncSetting() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()
        val setting = CameraAutoCorrectionSetting.FujifilmLocationSync
        assertEquals(
            CameraSettingState(supported = true, enabled = true),
            f.session("F").autoCorrectionSetting(setting),
        )

        f.orchestrator.setAutoCorrectionSetting("F", setting, false)
        runCurrent()
        val write = f.transport.steps("F").filterIsInstance<Step.Write>()
            .last { it.characteristicUuid == Fuji.LOCATION_SYNC_SETTING_UUID }
        assertEquals(Fuji.NOTIFICATION_SERVICE_UUID, write.serviceUuid)
        assertEquals(listOf(0x00, 0x00), write.value)
        assertEquals(false, f.session("F").autoCorrectionSetting(setting).enabled)

        // Switched back on in the camera menu.
        f.notify("F", Fuji.LOCATION_SYNC_SETTING_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertEquals(true, f.session("F").autoCorrectionSetting(setting).enabled)
    }

    @Test
    fun thePhonesLocationIsOnlyTrackedWhileTheCameraWantsIt() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.locationSyncSetting = byteArrayOf(0x00, 0x00)
        f.connect("F", FUJIFILM)
        runCurrent()
        assertTrue(f.session("F").isLocationReady)
        assertFalse(f.session("F").wantsLocation)
        assertFalse(f.source.active)
        // Known before the camera counted as ready: the location was never started.
        assertEquals(0, f.source.starts)

        // Switched on in the camera menu.
        f.notify("F", Fuji.LOCATION_SYNC_SETTING_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertTrue(f.source.active)

        // And off again from the app.
        f.orchestrator.setAutoCorrectionSetting(
            "F", CameraAutoCorrectionSetting.FujifilmLocationSync, false,
        )
        runCurrent()
        assertFalse(f.source.active)
    }

    @Test
    fun aRequestIsAnsweredEvenWhileLocationSyncLooksOff() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.locationSyncSetting = byteArrayOf(0x00, 0x00)
        f.connect("F", FUJIFILM)
        runCurrent()
        assertFalse(f.source.active)

        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertTrue(f.source.active)
        f.fix()
        runCurrent()

        assertEquals(1, f.transport.geotagWrites("F").size)
    }

    @Test
    fun aSonyCameraKeepsTheLocationRunningWhenAFujifilmCamerasSyncIsOff() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.locationSyncSetting = byteArrayOf(0x00, 0x00)
        f.connect("S", SONY)
        f.connect("F", FUJIFILM)
        runCurrent()

        assertFalse(f.session("F").wantsLocation)
        assertTrue(f.source.active)
    }

    // ---- switched off in standby ----

    @Test
    fun aCameraInStandbyKeepsGettingLocationsAtALowerRate() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.powerKeyState = byteArrayOf(0x00, 0x01) // off, standby
        f.connect("F", FUJIFILM)
        runCurrent()
        assertTrue(f.session("F").inStandby)
        assertTrue(f.source.active)
        assertEquals(60_000L, f.source.intervalMs)

        f.fix()
        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertEquals(1, f.transport.geotagWrites("F").size)

        // Switched on without reconnecting: noticed at the next request.
        f.transport.powerKeyState = byteArrayOf(0x01, 0x02)
        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()
        assertFalse(f.session("F").inStandby)
        assertEquals(10_000L, f.source.intervalMs) // back to the camera's interval
        assertEquals(2, f.transport.geotagWrites("F").size)
    }

    @Test
    fun aCameraThatFellAsleepCountsAsStandby() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()
        assertFalse(f.session("F").inStandby)

        // Automatic power off: the switch stays on, the camera runs in the background.
        f.transport.powerKeyState = byteArrayOf(0x01, 0x01)
        f.notify("F", Fuji.GEOTAG_REQUEST_UUID, byteArrayOf(0x01, 0x00))
        runCurrent()

        assertTrue(f.session("F").inStandby)
        assertEquals(60_000L, f.source.intervalMs)
    }

    @Test
    fun aCameraThatIsOnKeepsTheFullRate() = runTest {
        val f = Fixture(backgroundScope)
        f.transport.powerKeyState = byteArrayOf(0x00, 0x01)
        f.connect("F", FUJIFILM)
        f.connect("S", SONY)
        runCurrent()

        assertTrue(f.session("F").inStandby)
        assertEquals(5_000L, f.source.intervalMs)
    }

    @Test
    fun aCameraWithoutThePowerStateCountsAsOn() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM - Fuji.POWER_SWITCH_UUID)
        runCurrent()

        assertFalse(f.session("F").inStandby)
        assertTrue(f.session("F").isLocationReady)
        assertEquals(10_000L, f.source.intervalMs) // the camera asks every 10 s
    }

    // ---- intervals and CONNECT WHILE POWER OFF ----

    @Test
    fun setupWritesTheCamerasChosenInterval() = runTest {
        val f = Fixture(backgroundScope)
        f.dao.locationIntervalS = 30
        f.connect("F", FUJIFILM)
        runCurrent()

        val interval = f.transport.steps("F").filterIsInstance<Step.Write>()
            .single { it.characteristicUuid == Fuji.GEOTAG_SYNC_INTERVAL_UUID }
        assertEquals(listOf(30, 0), interval.value)
        // The phone doesn't need fixes more often than the camera asks.
        assertEquals(30_000L, f.source.intervalMs)
    }

    @Test
    fun aNewIntervalIsWrittenToAConnectedCameraAtOnce() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()

        f.dao.locationIntervalS = 60
        f.orchestrator.applyLocationIntervals("F")
        runCurrent()

        assertEquals(listOf(listOf(10, 0), listOf(60, 0)), f.transport.writes("F", Fuji.GEOTAG_SYNC_INTERVAL_UUID).map { v -> v.map { it.toInt() and 0xFF } })
        assertEquals(60, f.session("F").locationIntervalS)
        assertEquals(60_000L, f.source.intervalMs)
    }

    @Test
    fun standbyUsesTheStandbyInterval() = runTest {
        val f = Fixture(backgroundScope)
        f.dao.standbyIntervalS = 120
        f.transport.powerKeyState = byteArrayOf(0x00, 0x01)
        f.connect("F", FUJIFILM)
        runCurrent()

        assertEquals(120_000L, f.source.intervalMs)
    }

    @Test
    fun anUnknownStoredIntervalFallsBackToTheDefault() = runTest {
        val f = Fixture(backgroundScope)
        f.dao.locationIntervalS = 7
        f.connect("F", FUJIFILM)
        runCurrent()

        assertEquals(Fuji.GEOTAG_SYNC_INTERVAL_SECONDS, f.session("F").locationIntervalS)
    }

    @Test
    fun readsAndChangesConnectWhilePowerOff() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()
        val setting = CameraAutoCorrectionSetting.FujifilmConnectWhileOff
        assertEquals(
            CameraSettingState(supported = true, enabled = true),
            f.session("F").autoCorrectionSetting(setting),
        )

        f.orchestrator.setAutoCorrectionSetting("F", setting, false)
        runCurrent()

        assertEquals(listOf(0x00, 0x00), f.transport.writes("F", Fuji.CONNECT_WHILE_OFF_UUID).last().map { it.toInt() and 0xFF })
        assertEquals(false, f.session("F").autoCorrectionSetting(setting).enabled)
    }

    @Test
    fun aFujifilmCameraIsNotAskedForSonySettings() = runTest {
        val f = Fixture(backgroundScope)
        f.connect("F", FUJIFILM)
        runCurrent()

        val reads = f.transport.steps("F").filterIsInstance<Step.Read>().map { it.characteristicUuid }
        assertFalse(Sony.AUTO_TIME_CORRECTION_UUID in reads)
        assertFalse(Sony.AUTO_AREA_ADJUSTMENT_UUID in reads)
    }

    // ---- fixtures ----

    private sealed interface Step {
        data class Read(val characteristicUuid: String, val serviceUuid: String?) : Step
        data class Write(
            val characteristicUuid: String,
            val serviceUuid: String?,
            val value: List<Int>,
        ) : Step

        data class Subscribe(
            val characteristicUuid: String,
            val serviceUuid: String?,
            val indication: Boolean,
        ) : Step
    }

    private class Fixture(scope: CoroutineScope) {
        val source = FakeSource()
        val transport = FakeTransport()
        val dao = FakeDao()
        val orchestrator =
            CameraSessionOrchestrator(transport, source, dao, scope).also { it.start() }
        val events = mutableListOf<OrchestratorEvent>()

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                orchestrator.events.collect { events += it }
            }
        }

        fun connect(id: String, characteristics: Set<String>) {
            transport.characteristics[id] = characteristics
            transport.connected += id
            transport.emit(BleTransportEvent.Connected(id))
        }

        fun session(id: String) = orchestrator.registry.get(id)!!

        fun notify(id: String, uuid: String, value: ByteArray) =
            transport.emit(BleTransportEvent.CharacteristicChanged(id, uuid, value))

        fun fix(latitude: Double = 25.2048, longitude: Double = 55.2708, altitude: Double? = 5.0) {
            source.channel.trySend(
                GeoLocation(
                    latitude = latitude,
                    longitude = longitude,
                    horizontalAccuracyMeters = 5.0,
                    timestampMillis = Clock.System.now().toEpochMilliseconds(),
                    altitudeMeters = altitude,
                )
            )
        }
    }

    private class FakeSource : LocationSource {
        val channel = Channel<GeoLocation>(Channel.UNLIMITED)
        override val locations = channel.receiveAsFlow()
        var active = false
        var starts = 0
        var intervalMs = Sony.LOCATION_UPDATE_INTERVAL_MS

        override fun setUpdateInterval(intervalMs: Long) {
            this.intervalMs = intervalMs
        }

        override fun start(): Boolean {
            active = true
            starts++
            return true
        }

        override fun stop() {
            active = false
        }

        override fun hasPreciseAuthorization() = true
    }

    private class FakeTransport : BlePeripheralTransport {
        private val channel = Channel<BleTransportEvent>(Channel.UNLIMITED)
        override val events = channel.receiveAsFlow()
        val connected = mutableSetOf<String>()
        val characteristics = mutableMapOf<String, Set<String>>()
        private val operations = mutableListOf<Pair<String, BleOperation>>()
        var status = byteArrayOf(0x07, 0x96.toByte(), 0x00, 0x00)
        val failingSubscriptions = mutableSetOf<String>()
        var authErrorsLeft = 0
        var cameraName = "FUJIFILM-X100VI-1A2B"
        var locationSyncSetting = byteArrayOf(0x01, 0x00)
        var powerKeyState = byteArrayOf(0x01, 0x02)
        var connectWhileOff = byteArrayOf(0x01, 0x00)
        val reconnects = mutableListOf<String>()

        override fun reconnect(identifier: String): Boolean {
            reconnects += identifier
            return true
        }

        /** Keeps read responses back (in flight) until [releaseReads]. */
        var holdReads = false
        val heldReads = mutableListOf<BleTransportEvent.CharacteristicRead>()

        /** Discovery requests to ignore, like Android does while it re-reads services itself. */
        var discoveriesToDrop = 0

        fun releaseReads() {
            val released = heldReads.toList()
            heldReads.clear()
            released.forEach(::emit)
        }

        fun emit(event: BleTransportEvent) {
            channel.trySend(event)
        }

        fun discoveries(id: String) = operations.count { (device, op) ->
            device == id && op == BleOperation.DiscoverServices
        }

        /** The operations sent to [id] after service discovery, as comparable steps. */
        fun steps(id: String): List<Step> = operations.filter { it.first == id }.mapNotNull { (_, op) ->
            when (op) {
                is BleOperation.Read -> Step.Read(op.characteristicUuid, op.serviceUuid)
                is BleOperation.Write -> Step.Write(
                    op.characteristicUuid,
                    op.serviceUuid,
                    op.value.map { it.toInt() and 0xFF },
                )

                is BleOperation.Subscribe -> Step.Subscribe(op.characteristicUuid, op.serviceUuid, op.indication)
                BleOperation.DiscoverServices -> null
            }
        }.filterNot { it is Step.Write && it.characteristicUuid == Fuji.GEOTAG_CHARACTERISTIC_UUID }

        fun geotagWrites(id: String) = writes(id, Fuji.GEOTAG_CHARACTERISTIC_UUID)
        fun sonyLocationWrites(id: String) = writes(id, Sony.CHARACTERISTIC_UUID)

        fun writes(id: String, uuid: String) = operations.mapNotNull { (device, op) ->
            (op as? BleOperation.Write)?.takeIf {
                device == id && it.characteristicUuid.equals(uuid, ignoreCase = true)
            }?.value
        }

        override fun isConnected(identifier: String) = identifier in connected

        override fun hasCharacteristic(identifier: String, characteristicUuid: String) =
            characteristics[identifier].orEmpty().any { it.equals(characteristicUuid, ignoreCase = true) }

        override fun supportsWriteWithResponse(identifier: String, characteristicUuid: String) =
            hasCharacteristic(identifier, characteristicUuid) &&
                    !characteristicUuid.equals(Sony.AUTO_TIME_CORRECTION_UUID, ignoreCase = true) &&
                    !characteristicUuid.equals(Sony.AUTO_AREA_ADJUSTMENT_UUID, ignoreCase = true)

        override fun initiateDiscoverServices(identifier: String): Boolean {
            operations += identifier to BleOperation.DiscoverServices
            if (discoveriesToDrop > 0) {
                discoveriesToDrop--
                return true
            }
            emit(BleTransportEvent.ServicesDiscovered(identifier, success = true))
            return true
        }

        override fun initiateRead(identifier: String, characteristicUuid: String, serviceUuid: String?): Boolean {
            operations += identifier to BleOperation.Read(characteristicUuid, serviceUuid)
            if (!hasCharacteristic(identifier, characteristicUuid)) return false
            val isStatus = characteristicUuid.equals(Fuji.STATUS_CHARACTERISTIC_UUID, ignoreCase = true)
            val result = if (isStatus && authErrorsLeft > 0) {
                authErrorsLeft--
                BleOperationStatus.AuthError
            } else {
                BleOperationStatus.Success
            }
            // Sony's config read reports time zone support (byte 4, bit 0x02).
            val value = when {
                isStatus -> status
                characteristicUuid.equals(Fuji.NOTIFICATION_4_UUID, ignoreCase = true) ->
                    cameraName.encodeToByteArray() + ByteArray(5)
                characteristicUuid.equals(Fuji.LOCATION_SYNC_SETTING_UUID, ignoreCase = true) ->
                    locationSyncSetting
                characteristicUuid.equals(Fuji.POWER_SWITCH_UUID, ignoreCase = true) ->
                    powerKeyState
                characteristicUuid.equals(Fuji.CONNECT_WHILE_OFF_UUID, ignoreCase = true) ->
                    connectWhileOff
                else -> byteArrayOf(0, 0, 0, 0, 2)
            }
            val event = BleTransportEvent.CharacteristicRead(identifier, characteristicUuid, value, result)
            if (holdReads) heldReads += event else emit(event)
            return true
        }

        override fun initiateWrite(
            identifier: String,
            characteristicUuid: String,
            value: ByteArray,
            serviceUuid: String?,
        ): Boolean {
            operations += identifier to BleOperation.Write(characteristicUuid, value, serviceUuid)
            if (!hasCharacteristic(identifier, characteristicUuid)) return false
            emit(
                BleTransportEvent.CharacteristicWritten(
                    identifier,
                    characteristicUuid,
                    BleOperationStatus.Success,
                )
            )
            return true
        }

        override fun initiateSubscribe(
            identifier: String,
            characteristicUuid: String,
            enable: Boolean,
            indication: Boolean,
            serviceUuid: String?,
        ): Boolean {
            operations += identifier to
                    BleOperation.Subscribe(characteristicUuid, enable, indication, serviceUuid)
            if (!hasCharacteristic(identifier, characteristicUuid)) return false
            val failed = failingSubscriptions.any { it.equals(characteristicUuid, ignoreCase = true) }
            emit(
                BleTransportEvent.SubscriptionChanged(
                    identifier,
                    characteristicUuid,
                    enable,
                    if (failed) BleOperationStatus.Failure else BleOperationStatus.Success,
                )
            )
            return true
        }
    }

    private class FakeDao : CameraDeviceDAO {
        val devices = mutableMapOf<String, CameraDevice>()
        var timeSyncEnabled: Boolean? = null
        override suspend fun getAllCameraDevices() = devices.values.toList()
        override fun observeAllDevices() = flowOf(emptyList<CameraDevice>())
        override suspend fun insertDevice(device: CameraDevice) = Unit
        override suspend fun setDeviceName(deviceId: String, name: String, isCustom: Boolean) {
            devices[deviceId]?.let {
                devices[deviceId] = it.copy(deviceName = name, deviceNameIsCustom = isCustom)
            }
        }
        override suspend fun getDeviceName(address: String): String? = null
        override suspend fun deleteDevice(device: CameraDevice) = Unit
        override suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean) = Unit
        override suspend fun isDeviceAlwaysOnEnabled(address: String) = false
        override suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean) = Unit
        override suspend fun isDeviceEnabled(address: String) = true
        override suspend fun findDeviceEnabled(address: String): Boolean? = true
        override suspend fun getAlwaysOnEnabledDeviceCount() = 0
        override suspend fun setRemoteControlEnabled(deviceId: String, enabled: Boolean) = 0
        override suspend fun isRemoteControlEnabled(address: String) = false
        override suspend fun getHandshakeDelayMs(address: String): Long? = 0
        override suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long) = Unit
        override suspend fun findTimeSyncEnabled(address: String): Boolean? = timeSyncEnabled
        override suspend fun setTimeSyncEnabled(deviceId: String, enabled: Boolean) {
            timeSyncEnabled = enabled
        }
        var locationIntervalS: Int? = null
        var standbyIntervalS: Int? = null
        override suspend fun findLocationIntervalS(address: String): Int? = locationIntervalS
        override suspend fun setLocationIntervalS(deviceId: String, seconds: Int) {
            locationIntervalS = seconds
        }
        override suspend fun findStandbyIntervalS(address: String): Int? = standbyIntervalS
        override suspend fun setStandbyIntervalS(deviceId: String, seconds: Int) {
            standbyIntervalS = seconds
        }
        var sendIntervalS: Int? = null
        var keepAwakeEnabled: Boolean? = null
        override suspend fun findSendIntervalS(address: String): Int? = sendIntervalS
        override suspend fun setSendIntervalS(deviceId: String, seconds: Int) {
            sendIntervalS = seconds
        }
        override suspend fun findKeepAwakeEnabled(address: String): Boolean? = keepAwakeEnabled
        override suspend fun setKeepAwakeEnabled(deviceId: String, enabled: Boolean) {
            keepAwakeEnabled = enabled
        }
    }

    private companion object {
        val FUJIFILM: Set<String> = setOf(
            Fuji.STATUS_CHARACTERISTIC_UUID,
            Fuji.IDENTIFIER_CHARACTERISTIC_UUID,
            Fuji.GEOTAG_CHARACTERISTIC_UUID,
            Fuji.SHUTTER_CHARACTERISTIC_UUID,
            Fuji.UTC_TIME_ZONE_UUID,
            Fuji.POWER_SWITCH_UUID,
            Fuji.CONNECT_WHILE_OFF_UUID,
        ) + (Fuji.REQUIRED_SUBSCRIPTIONS + Fuji.OPTIONAL_SUBSCRIPTIONS).map { it.characteristicUuid }

        val SONY: Set<String> = setOf(
            Sony.CHARACTERISTIC_UUID,
            Sony.CHARACTERISTIC_READ_UUID,
            Sony.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND,
            Sony.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND,
            Sony.TIME_SYNC_CHARACTERISTIC_UUID,
        )

        fun ByteArray.int32(offset: Int): Int =
            (this[offset].toInt() and 0xFF) or
                    ((this[offset + 1].toInt() and 0xFF) shl 8) or
                    ((this[offset + 2].toInt() and 0xFF) shl 16) or
                    ((this[offset + 3].toInt() and 0xFF) shl 24)
    }
}
