package com.sasch.cameragps.sharednew.bluetooth.location

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleGattPort
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationDataConfig
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationPacketBuilder
import com.sasch.cameragps.sharednew.bluetooth.coordinator.PlatformTimeZoneInfo
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmPacketBuilder
import com.sasch.cameragps.sharednew.bluetooth.session.CameraProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

sealed interface LocationEvent {
    /** First fix of the session was accepted. */
    data object FirstFixAcquired : LocationEvent

    /** A periodic tick fired with no fix available. */
    data object NoLocationAvailable : LocationEvent
}

/**
 * Shared location transmission: freshness/accuracy gating, periodic resend and
 * send-to-ready-sessions. Unifies the former Android
 * `LocationTransmissionCoordinator` and iOS `IosLocationTransmissionManager`.
 *
 * Sony cameras get the location pushed every [SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS];
 * Fujifilm cameras ask for it ([onLocationRequested]) and only get answers.
 *
 * All packet sends go through [port] (the queue-backed [BleGattPort]) so they
 * are serialized with every other BLE operation.
 */
class LocationTransmissionManager(
    private val source: LocationSource,
    private val readySessions: () -> Set<String>,
    private val configFor: (String) -> LocationDataConfig?,
    private val port: BleGattPort,
    private val scope: CoroutineScope,
    /** iOS: app-level transmission toggle. Android: always allowed. */
    private val isTransmissionAllowed: () -> Boolean = { true },
    private val protocolFor: (String) -> CameraProtocol = { CameraProtocol.Sony },
) {
    private val log = logging()

    private val _events = Channel<LocationEvent>(Channel.UNLIMITED)
    val events: Flow<LocationEvent> = _events.receiveAsFlow()

    private val _isActive = MutableStateFlow(false)

    /** `true` while location updates and the periodic loop are running. */
    val isActive: StateFlow<Boolean> = _isActive

    private val _isTransmitting = MutableStateFlow(false)

    /** Tracking is active and at least one location packet has been accepted by the BLE queue. */
    val isTransmitting: StateFlow<Boolean> = _isTransmitting

    private var latest: GeoLocation? = null
    private var hasSessionLocation = false
    private var collectJob: Job? = null
    private var periodicJob: Job? = null

    /** Cameras that asked for a location before one could be sent. */
    private val pendingRequests = mutableSetOf<String>()

    /**
     * A device finished its handshake: make sure tracking runs and give the
     * camera a cached fix immediately instead of waiting for the next tick
     * (Sony), or answer a request that arrived early (Fujifilm).
     */
    fun onDeviceReady(identifier: String) {
        val id = identifier.uppercase()
        startIfNeeded()
        if (isPushBased(id)) sendImmediateIfCached(id) else answerPendingRequests()
    }

    /**
     * A camera asked for the current location (Fujifilm geotag request). Answered
     * right away with the latest fix, or as soon as the camera is ready and a fix
     * is available.
     */
    fun onLocationRequested(identifier: String) {
        val id = identifier.uppercase()
        if (!isTransmissionAllowed()) return
        pendingRequests += id
        answerPendingRequests()
        if (id in pendingRequests) {
            log.d { "Location request from $id queued until a fix is available" }
        }
    }

    /** Drop state kept for [identifier] (it disconnected). */
    fun forgetDevice(identifier: String) {
        pendingRequests -= identifier.uppercase()
    }

    /**
     * Re-evaluate whether tracking should run. Called after disconnects or
     * app-enable toggles; stops everything when no ready session remains.
     */
    fun updateTracking() {
        if (readySessions().isEmpty() || !isTransmissionAllowed()) {
            stopUpdates()
        } else {
            startIfNeeded()
        }
    }

    /** Tear down all location state (service destroy / force shutdown). */
    fun shutdown() {
        stopUpdates()
        latest = null
    }

    private fun startIfNeeded() {
        if (!isTransmissionAllowed()) return
        if (readySessions().isEmpty()) return
        if (_isActive.value) return

        // Discard a fix cached from a previous session if it is too old
        latest = latest?.takeIf { hasSessionLocation || !isTooOld(it) }

        if (!source.start()) {
            log.e { "Location source could not be started, cannot begin transmission" }
            return
        }
        _isActive.value = true
        log.i { "Starting location transmission" }

        collectJob = scope.launch {
            source.locations.collect { onNewLocation(it) }
        }
        periodicJob = scope.launch {
            while (isActive) {
                delay(SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS.milliseconds)
                val current = latest
                if (current != null) {
                    log.d { "Periodic timer – sending location to ready sessions" }
                    runCatching { sendToPushSessions(current) }
                        .onFailure { log.e(it, msg = { "Error sending location" }) }
                } else {
                    log.w { "Periodic timer – no location available to send" }
                    _events.trySend(LocationEvent.NoLocationAvailable)
                }
            }
        }
    }

    private fun stopUpdates() {
        _isTransmitting.value = false
        if (_isActive.value) {
            source.stop()
            log.i { "Stopped location transmission" }
        }
        collectJob?.cancel()
        collectJob = null
        periodicJob?.cancel()
        periodicJob = null
        _isActive.value = false
        hasSessionLocation = false
        pendingRequests.clear()
    }

    private fun onNewLocation(location: GeoLocation) {
        if (!isTransmissionAllowed()) return
        val current = latest
        if (!shouldUpdateLocation(location, current)) return

        val hadLocationBefore = current != null
        hasSessionLocation = true

        // Send right away when there was no usable fix yet; otherwise the
        // periodic loop picks the new fix up on its next tick.
        if (current == null || !isFreshFix(current)) {
            runCatching { sendToPushSessions(location) }
                .onFailure { log.e(it, msg = { "Error sending location" }) }
        }
        latest = location
        answerPendingRequests()

        if (!hadLocationBefore) {
            _events.trySend(LocationEvent.FirstFixAcquired)
        }
    }

    private fun sendImmediateIfCached(identifier: String) {
        if (!isTransmissionAllowed()) return
        if (identifier !in readySessions()) return
        latest?.let {
            if (hasSessionLocation || isFreshFix(it)) {
                sendToDevice(identifier, it)
            }
        }
    }

    private fun isPushBased(identifier: String) = protocolFor(identifier) == CameraProtocol.Sony

    private fun sendToPushSessions(location: GeoLocation) {
        readySessions().filter(::isPushBased).forEach { sendToDevice(it, location) }
    }

    /** Answer requests of ready cameras once a fix exists; others keep waiting. */
    private fun answerPendingRequests() {
        if (!isTransmissionAllowed()) return
        val location = latest ?: return
        val ready = readySessions()
        pendingRequests.filter { it in ready }.forEach { id ->
            pendingRequests -= id
            sendToDevice(id, location)
        }
    }

    private fun sendToDevice(identifier: String, location: GeoLocation) {
        val queued = when (protocolFor(identifier)) {
            CameraProtocol.Sony -> {
                val config =
                    configFor(identifier) ?: LocationDataConfig(shouldSendTimeZoneAndDst = false)
                val packet = LocationPacketBuilder.buildLocationDataPacket(
                    config,
                    location.latitude,
                    location.longitude,
                    PlatformTimeZoneInfo(),
                )
                port.writeCharacteristic(identifier, SonyBluetoothConstants.CHARACTERISTIC_UUID, packet)
            }

            CameraProtocol.FujifilmSecure -> {
                val packet = FujifilmPacketBuilder.buildGeotagPacket(
                    location.latitude,
                    location.longitude,
                    location.altitudeMeters,
                )
                log.d { "Answering the geotag request of $identifier (${packet.size} bytes)" }
                port.writeCharacteristic(
                    identifier,
                    FujifilmBluetoothConstants.GEOTAG_CHARACTERISTIC_UUID,
                    packet,
                    FujifilmBluetoothConstants.GEOTAG_SERVICE_UUID,
                )
            }
        }
        if (queued && _isActive.value) _isTransmitting.value = true
    }

    private fun shouldUpdateLocation(new: GeoLocation, current: GeoLocation?): Boolean {
        current ?: return true
        if (new.horizontalAccuracyMeters < 0 || current.horizontalAccuracyMeters < 0) return true

        val accuracyDifference = new.horizontalAccuracyMeters - current.horizontalAccuracyMeters
        if (accuracyDifference <= SonyBluetoothConstants.ACCURACY_THRESHOLD_METERS) return true

        val ageMs = new.timestampMillis - current.timestampMillis
        return ageMs / 1000 > MAX_IMMEDIATE_FIX_AGE_SECONDS
    }

    private fun isTooOld(location: GeoLocation): Boolean =
        (nowMillis() - location.timestampMillis) / 1000 > MAX_IMMEDIATE_FIX_AGE_SECONDS

    private fun isFreshFix(location: GeoLocation): Boolean =
        (nowMillis() - location.timestampMillis) / 1000 <= MAX_IMMEDIATE_FIX_AGE_SECONDS

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val MAX_IMMEDIATE_FIX_AGE_SECONDS = 30
    }
}
