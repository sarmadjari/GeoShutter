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
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import com.sasch.cameragps.sharednew.bluetooth.session.CameraProtocol
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
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
        )
        assertEquals(expected, f.transport.steps("F"))
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
        val orchestrator =
            CameraSessionOrchestrator(transport, source, FakeDao(), scope).also { it.start() }
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

        override fun start(): Boolean {
            active = true
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
            val value = if (isStatus) status else byteArrayOf(0, 0, 0, 0, 2)
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
        override suspend fun getAllCameraDevices() = emptyList<CameraDevice>()
        override fun observeAllDevices() = flowOf(emptyList<CameraDevice>())
        override suspend fun insertDevice(device: CameraDevice) = Unit
        override suspend fun setDeviceName(deviceId: String, name: String, isCustom: Boolean) = Unit
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
    }

    private companion object {
        val FUJIFILM: Set<String> = setOf(
            Fuji.STATUS_CHARACTERISTIC_UUID,
            Fuji.IDENTIFIER_CHARACTERISTIC_UUID,
            Fuji.GEOTAG_CHARACTERISTIC_UUID,
            Fuji.SHUTTER_CHARACTERISTIC_UUID,
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
