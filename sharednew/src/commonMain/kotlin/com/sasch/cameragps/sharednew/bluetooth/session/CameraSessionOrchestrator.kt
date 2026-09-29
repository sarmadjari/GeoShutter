package com.sasch.cameragps.sharednew.bluetooth.session

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionCoordinator
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleSessionEvent
import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteCommand
import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteControlCoordinator
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.CameraDetection
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmHandshakeResult
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmNotification
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmPacketBuilder
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmSessionController
import com.sasch.cameragps.sharednew.bluetooth.location.LocationEvent
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import com.sasch.cameragps.sharednew.bluetooth.location.LocationTransmissionManager
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationQueue
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationResult
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The common session orchestrator. Owns the per-device session registry, the
 * sequential operation queue and the location transmission manager; routes
 * transport events into the existing shared [BleSessionCoordinator] and
 * [RemoteControlCoordinator].
 *
 * This unifies what `LocationSenderService` (+ its adapter coordinators) did
 * on Android and what `IosBluetoothController`'s delegates/init-block did on
 * iOS. Platform shells only own sockets and lifecycle:
 * they feed connects/disconnects in via the transport and react to [events].
 *
 * Must be driven from a single-threaded [scope]
 * (`Dispatchers.Main.immediate` on both platforms).
 */
class CameraSessionOrchestrator(
    private val transport: BlePeripheralTransport,
    locationSource: LocationSource,
    private val deviceDao: CameraDeviceDAO,
    private val scope: CoroutineScope,
    private val pairingPolicy: PairingRetryPolicy = PairingRetryPolicy(),
    /**
     * Consulted when a handshake completes. Return `false` to abort the session
     * (iOS passes its device-enabled check here and disconnects inside the
     * lambda when disabled).
     */
    private val shouldRemainConnected: suspend (String) -> Boolean = { true },
    /** iOS: app-level transmission toggle for the location manager. */
    isTransmissionAllowed: () -> Boolean = { true },
    /** How long service discovery may take, including the iOS pairing gate. */
    discoveryTimeoutMs: Long = BleOperationQueue.DEFAULT_DISCOVERY_TIMEOUT_MS,
) : CameraAutoCorrectionControls {
    private val log = logging()

    val registry = CameraSessionRegistry()

    private val queue: BleOperationQueue =
        BleOperationQueue(transport, scope, discoveryTimeoutMs = discoveryTimeoutMs, shouldExecute = { id, operation ->
            // Parked location packets require a completed handshake. Camera-reported
            // location status is advisory and does not gate transmission.
            when {
                operation !is BleOperation.Write -> true
                operation.characteristicUuid.equals(
                    SonyBluetoothConstants.CHARACTERISTIC_UUID,
                    true
                ) || operation.characteristicUuid.equals(
                    FujifilmBluetoothConstants.GEOTAG_CHARACTERISTIC_UUID,
                    true
                ) ->
                registry.get(id)?.isLocationReady == true

                else -> true
            }
    })
    private val port = QueuedBleGattPort(queue, transport, registry)
    private val autoCorrection = CameraAutoCorrectionController(port, registry, scope)
    private val remoteControl: RemoteControlCoordinator = RemoteControlCoordinator(port, scope)
    private val sessionCoordinator = BleSessionCoordinator(port, remoteControl)
    private val fujifilm = FujifilmSessionController(port, pairingPolicy)

    val locationManager = LocationTransmissionManager(
        source = locationSource,
        readySessions = registry::readyIdentifiers,
        configFor = sessionCoordinator::getLocationDataConfig,
        port = port,
        scope = scope,
        isTransmissionAllowed = isTransmissionAllowed,
        protocolFor = { id -> registry.get(id)?.protocol ?: CameraProtocol.Sony },
        wantsLocation = { id -> registry.get(id)?.let { it.wantsLocation && !it.cameraOff } ?: true },
        updateIntervalMsFor = { id ->
            registry.get(id)?.locationUpdateIntervalMs ?: SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS
        },
    )

    /**
     * SharedFlow, not a Channel: it supports several collectors (Android's app
     * graph and service both subscribe) and drops events while nobody collects
     * (a stopped Android service must not replay stale sounds on restart).
     * Subscribers must attach before the first connect — true for both shells,
     * which subscribe during construction on the main thread.
     */
    private val _events = MutableSharedFlow<OrchestratorEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<OrchestratorEvent> = _events

    override val sessions: StateFlow<Map<String, CameraSession>> get() = registry.sessions

    override fun refreshAutoCorrectionSettings(identifier: String) =
        autoCorrection.refresh(identifier)

    override fun applyLocationIntervals(identifier: String) {
        val id = identifier.uppercase()
        val session = registry.get(id) ?: return
        if (session.protocol != CameraProtocol.FujifilmSecure) {
            // The registry observer applies the new interval to the phone's location.
            scope.launch { loadSonySendInterval(id) }
            return
        }
        scope.launch {
            val before = registry.get(id)?.locationIntervalS
            val intervalS = loadFujifilmIntervals(id)
            // The camera takes a new request interval at once (it echoes it).
            if (intervalS != before && registry.get(id)?.phase == BleSessionPhase.Transmitting) {
                fujifilm.writeSyncInterval(id, intervalS)
            }
        }
    }

    override fun setAutoCorrectionSetting(
        identifier: String,
        setting: CameraAutoCorrectionSetting,
        enabled: Boolean
    ) =
        autoCorrection.set(identifier, setting, enabled)

    override fun applyKeepAwake(identifier: String) {
        val id = identifier.uppercase()
        if (registry.get(id)?.isLocationReady != true || isFujifilm(id)) return
        scope.launch { updateKeepAwake(id) }
    }


    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            transport.events.collect { event ->
                // Completion matching must run before any other routing so the
                // queue can release its lane for the next operation.
                val completedOperation = queue.onTransportEvent(event)
                handleTransportEvent(event, completedOperation)
            }
        }
        // A Fujifilm camera's location sync, power switch or intervals changed, a Sony
        // camera's interval changed or it was switched off: the phone's location is only
        // tracked while a ready camera wants it, as often as the most demanding one needs.
        scope.launch {
            registry.sessions
                .map { sessions ->
                    val ready = sessions.values.filter { it.isLocationReady }
                    val wanting = ready.filter { it.wantsLocation && !it.cameraOff }
                    (ready - wanting.toSet()).map { it.identifier }.toSet() to
                            wanting.map { it.identifier to it.locationUpdateIntervalMs }.toSet()
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { locationManager.updateTracking() }
        }
        scope.launch {
            sessionCoordinator.events.collect { handleSessionEvent(it) }
        }
        scope.launch {
            locationManager.events.collect { event ->
                when (event) {
                    LocationEvent.FirstFixAcquired ->
                        _events.tryEmit(OrchestratorEvent.FirstLocationAcquired)

                    LocationEvent.NoLocationAvailable ->
                        _events.tryEmit(OrchestratorEvent.LocationUnavailable)
                }
            }
        }
    }

    // ---- Public API for platform shells ----

    /** A connection attempt was started by the shell. */
    fun onConnectRequested(identifier: String) {
        registry.upsert(identifier) { it.copy(phase = BleSessionPhase.Connecting) }
    }

    /** A connection attempt failed before any transport event (e.g. Bluetooth off). */
    fun onConnectFailed(identifier: String) {
        registry.updateIfPresent(identifier) { it.copy(phase = BleSessionPhase.Error) }
    }

    fun triggerRemoteShutter(identifier: String): Boolean =
        sendRemoteCommand(identifier, RemoteCommand.ShutterFullPress)

    /**
     * Send a remote-control command. The coordinator gates on an active
     * connection and remote feature; the write is serialized via the queue.
     */
    fun sendRemoteCommand(identifier: String, command: RemoteCommand): Boolean =
        remoteControl.sendCommand(identifier, command)

    /**
     * Run one full shutter sequence (half press → focus delay → full press →
     * releases → wait for camera ready), as opposed to [triggerRemoteShutter]'s
     * single press with ack-driven release.
     */
    fun triggerShutterSequence(identifier: String): Boolean =
        remoteControl.startShutterSequence(identifier)

    fun setRemoteMonitoring(identifier: String, enabled: Boolean) {
        val id = identifier.uppercase()
        if (enabled) {
            // The remote is a Sony feature.
            if (isFujifilm(id)) return
            remoteControl.startRemoteStatusMonitoring(id)
        } else {
            remoteControl.cancelProbe(id)
            port.setRemoteFeatureActive(id, false)
        }
    }

    fun clearDevice(identifier: String) {
        val id = identifier.uppercase()
        forgetFujifilmConnection(id)
        keepAwakeJobs.remove(id)?.cancel()
        sonyCameraOffRetries.remove(id)?.cancel()
        autoCorrection.clear(id)
        queue.cancelOperations(id, "session cleared")
        sessionCoordinator.clearSession(id)
        locationManager.forgetDevice(id)
        restartingSetup -= id
        setupGeneration.remove(id)
        registry.remove(id)
        locationManager.updateTracking()
    }

    fun shutdownAll() {
        keepAwakeJobs.values.forEach { it.cancel() }
        keepAwakeJobs.clear()
        sonyCameraOffRetries.values.forEach { it.cancel() }
        sonyCameraOffRetries.clear()
        autoCorrection.clearAll()
        lastFujifilmTimeRequest.clear()
        fujifilmWatchdogs.values.forEach { it.cancel() }
        fujifilmWatchdogs.clear()
        sessionCoordinator.clearAllSessions()
        queue.shutdown("shutdown")
        locationManager.shutdown()
        registry.clear()
    }

    // ---- Transport event routing ----

    private fun handleTransportEvent(event: BleTransportEvent, completedOperation: BleOperation?) {
        // After a restart, results of the cancelled setup no longer match a queued
        // operation; they must not drive the new handshake.
        if (completedOperation == null && event.identifier.uppercase() in restartingSetup &&
            (event is BleTransportEvent.CharacteristicRead ||
                    event is BleTransportEvent.CharacteristicWritten ||
                    event is BleTransportEvent.SubscriptionChanged)
        ) {
            log.d { "Ignoring a result of the cancelled setup of ${event.identifier}" }
            return
        }
        // The Fujifilm handshake awaits its own results through the queue; the
        // Sony handlers below must not react to them (or restart a Sony handshake).
        if (isFujifilm(event.identifier)) {
            when (event) {
                is BleTransportEvent.Connected,
                is BleTransportEvent.Disconnected,
                is BleTransportEvent.ServicesChanged -> Unit

                is BleTransportEvent.CharacteristicChanged -> {
                    handleFujifilmNotification(event)
                    return
                }

                else -> return
            }
        }
        when (event) {
            is BleTransportEvent.Connected -> handleConnected(event.identifier)

            is BleTransportEvent.Disconnected -> {
                log.i { "Device ${event.identifier} disconnected (status=${event.statusCode})" }
                clearDevice(event.identifier)
                _events.tryEmit(OrchestratorEvent.DeviceDisconnected(event.identifier.uppercase()))
            }

            is BleTransportEvent.CharacteristicWritten -> {
                // These operations consume their own queue result, including failures.
                if (CameraAutoCorrectionSetting.fromUuid(event.characteristicUuid) != null) return
                // DD30 is shared by lock acquisition and release. A release
                // completion must not advance/restart the GPS-enable handshake.
                val isLocationLockRelease = completedOperation is BleOperation.Write &&
                        completedOperation.characteristicUuid.equals(
                            SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND, true,
                        ) && completedOperation.value.contentEquals(SonyBluetoothConstants.LOCATION_LOCK_RELEASE_COMMAND)
                if (!isLocationLockRelease) handleWritten(event)
            }

            is BleTransportEvent.CharacteristicRead -> {
                if (event.characteristicUuid.equals(
                        SonyBluetoothConstants.CHARACTERISTIC_READ_UUID,
                        true
                    )
                ) handleRead(
                    event
                )
            }

            is BleTransportEvent.SubscriptionChanged -> handleSubscriptionChanged(event)

            is BleTransportEvent.CharacteristicChanged -> handleCharacteristicChanged(event)

            is BleTransportEvent.ServicesDiscovered -> Unit // consumed by the queue

            is BleTransportEvent.ServicesChanged -> handleServicesChanged(event.identifier)
        }
    }

    private fun handleCharacteristicChanged(event: BleTransportEvent.CharacteristicChanged) {
        // The coordinator updates the camera-reported warning in the registry.
        // Neither status value releases the GPS lock or restarts the handshake.
        sessionCoordinator.onCharacteristicChanged(
            event.identifier, event.characteristicUuid, event.value,
        )
    }

    private fun handleConnected(identifier: String) {
        val id = identifier.uppercase()
        forgetFujifilmConnection(id)
        autoCorrection.clear(id)
        log.i { "Device $id connected" }
        registry.upsert(id) {
            it.copy(
                phase = BleSessionPhase.Connected,
                pairingRetryCount = 0,
                hasRetriedConfigRead = false,
                locationDisabledByCamera = false,
                autoTimeCorrection = CameraSettingState(),
                autoAreaAdjustment = CameraSettingState(),
                fujifilmLocationSync = CameraSettingState(),
                fujifilmConnectWhileOff = CameraSettingState(),
                cameraResponding = false,
                inStandby = false,
            )
        }
        _events.tryEmit(OrchestratorEvent.DeviceConnected(id))

        scope.launch {
            val delayMs = runCatching { deviceDao.getHandshakeDelayMs(id) }.getOrNull() ?: 0L
            if (delayMs > 0) {
                // Some cameras stall their own boot while servicing BLE traffic;
                // the per-device delay lets them finish starting first
                log.i { "Delaying connection setup for $id by ${delayMs}ms" }
                delay(delayMs.milliseconds)
                if (registry.get(id) == null || !transport.isConnected(id)) return@launch
            }
            runDiscoveryAndHandshake(id)
        }
    }

    private suspend fun runDiscoveryAndHandshake(id: String, afterServicesChanged: Boolean = false) {
        val generation = setupGeneration[id]
        registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.DiscoveringServices) }
        val discovery = if (afterServicesChanged) {
            rediscover(id, generation)
        } else {
            queue.execute(id, BleOperation.DiscoverServices)
        }
        // A newer setup (services changed) owns the session now.
        if (setupGeneration[id] != generation) return
        restartingSetup -= id
        when (discovery) {
            is BleOperationResult.Success -> when (fujifilm.detect(id)) {
                CameraDetection.Sony -> {
                    // Detection decides the protocol on every connect, so a session
                    // reconnected without a disconnect event can't keep a stale one.
                    registry.updateIfPresent(id) { it.copy(protocol = CameraProtocol.Sony) }
                    sessionCoordinator.beginHandshake(id)
                }

                CameraDetection.FujifilmSecure -> runFujifilmHandshake(id)
                CameraDetection.FujifilmLegacy -> {
                    log.w { "Camera $id uses the legacy Fujifilm protocol, which is not supported" }
                    registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Error) }
                }
            }

            is BleOperationResult.Cancelled -> Unit // disconnected meanwhile
            else -> {
                log.e { "Service discovery failed for $id" }
                registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Error) }
            }
        }
    }

    private fun handleWritten(event: BleTransportEvent.CharacteristicWritten) {
        val id = event.identifier.uppercase()
        when (event.status) {
            BleOperationStatus.AuthError -> retryAfterAuthError(id) {
                // Restart the handshake after pairing settles (previous iOS behavior)
                sessionCoordinator.beginHandshake(id)
            }

            else -> {
                resetPairingRetries(id)
                if (event.attError == SonyBluetoothConstants.ATT_ERROR_NOT_AVAILABLE &&
                    event.characteristicUuid.lowercase() in SONY_LOCATION_OPERATIONS
                ) {
                    onSonyCameraOff(id)
                }
                sessionCoordinator.onCharacteristicWrite(
                    id,
                    event.characteristicUuid,
                    event.status == BleOperationStatus.Success,
                )
            }
        }
    }

    private fun handleRead(event: BleTransportEvent.CharacteristicRead) {
        val id = event.identifier.uppercase()
        when (event.status) {
            BleOperationStatus.AuthError -> retryAfterAuthError(id) {
                port.readCharacteristic(id, event.characteristicUuid)
            }

            BleOperationStatus.Failure -> {
                val isConfigRead = event.characteristicUuid.equals(
                    SonyBluetoothConstants.CHARACTERISTIC_READ_UUID,
                    ignoreCase = true,
                )
                val session = registry.get(id)
                if (isConfigRead && session != null && !session.hasRetriedConfigRead) {
                    // One-shot retry for the intermittent GATT 133 read failure
                    log.w { "Config read failed for $id, retrying once" }
                    registry.updateIfPresent(id) { it.copy(hasRetriedConfigRead = true) }
                    port.readCharacteristic(id, event.characteristicUuid)
                } else {
                    sessionCoordinator.onCharacteristicRead(id, event.value, false)
                }
            }

            BleOperationStatus.Success -> {
                resetPairingRetries(id)
                sessionCoordinator.onCharacteristicRead(id, event.value, true)
            }
        }
    }

    private fun handleSubscriptionChanged(event: BleTransportEvent.SubscriptionChanged) {
        val id = event.identifier.uppercase()
        when (event.status) {
            BleOperationStatus.AuthError -> retryAfterAuthError(id) {
                port.subscribeToNotifications(id, event.characteristicUuid)
            }

            BleOperationStatus.Failure ->
                log.w { "Subscription change failed for $id (${event.characteristicUuid})" }

            BleOperationStatus.Success -> resetPairingRetries(id)
        }
    }

    private fun retryAfterAuthError(identifier: String, retry: () -> Unit) {
        val session = registry.get(identifier) ?: return
        val attempts = session.pairingRetryCount + 1
        if (attempts > pairingPolicy.maxRetries) {
            log.e { "Pairing retries exhausted for $identifier" }
            _events.tryEmit(OrchestratorEvent.PairingFailed(identifier))
            return
        }
        log.w { "Auth error for $identifier, retry $attempts/${pairingPolicy.maxRetries}" }
        registry.updateIfPresent(identifier) { it.copy(pairingRetryCount = attempts) }
        scope.launch {
            val delayMs = if (attempts == 1) {
                pairingPolicy.firstRetryDelayMs
            } else {
                pairingPolicy.retryDelayMs
            }
            if (delayMs > 0) {
                delay(delayMs.milliseconds)
            }
            if (transport.isConnected(identifier)) {
                retry()
            }
        }
    }

    private fun resetPairingRetries(identifier: String) {
        val session = registry.get(identifier) ?: return
        if (session.pairingRetryCount != 0) {
            registry.updateIfPresent(identifier) { it.copy(pairingRetryCount = 0) }
        }
    }

    // ---- Coordinator event routing ----

    private suspend fun handleSessionEvent(event: BleSessionEvent) {
        when (event) {
            is BleSessionEvent.PhaseChanged ->
                registry.updateIfPresent(event.identifier) { it.copy(phase = event.phase) }

            is BleSessionEvent.HandshakeComplete -> handleHandshakeComplete(event.identifier)
        }
    }

    private suspend fun handleHandshakeComplete(identifier: String) {
        val id = identifier.uppercase()
        if (!shouldRemainConnected(id)) {
            log.i { "Device $id should not remain connected, skipping session start" }
            return
        }
        log.i { "Handshake complete for $id" }
        if (!isFujifilm(id)) {
            // Accepted the GPS setup: switched on (again).
            sonyCameraOffRetries.remove(id)?.cancel()
            loadSonySendInterval(id)
        }
        registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Transmitting, cameraOff = false) }
        locationManager.onDeviceReady(id)

        // Fujifilm cameras had their settings read during setup; the remote is Sony-only.
        if (!isFujifilm(id)) {
            autoCorrection.refresh(id)

            val remoteEnabled = runCatching { deviceDao.isRemoteControlEnabled(id) }
                .getOrDefault(false)
            if (remoteEnabled) {
                remoteControl.startRemoteStatusMonitoring(id)
            }
            updateKeepAwake(id)
        }
        _events.tryEmit(OrchestratorEvent.HandshakeCompleted(id))
    }

    // ---- Sony ----

    private suspend fun loadSonySendInterval(id: String) {
        val seconds = runCatching { deviceDao.findSendIntervalS(id) }.getOrNull()
            ?.takeIf { it in SonyBluetoothConstants.SEND_INTERVALS_SECONDS }
            ?: SonyBluetoothConstants.SEND_INTERVAL_SECONDS
        registry.updateIfPresent(id) { it.copy(sendIntervalS = seconds) }
    }

    /** Sony cameras whose power save GeoShutter holds off (the camera's "Keep the camera awake"). */
    private val keepAwakeJobs = mutableMapOf<String, Job>()

    /**
     * In power save a Sony camera ends the connection, so photos taken right after waking
     * it get no location until the phone reconnects. With the option on, the camera gets
     * Creators' App's auto power off avoidance while it is connected, which keeps it awake.
     */
    private suspend fun updateKeepAwake(id: String) {
        val enabled = runCatching { deviceDao.findKeepAwakeEnabled(id) }.getOrNull() == true
        val session = registry.get(id)
        val wanted = enabled && session != null && session.isLocationReady && !session.cameraOff &&
                transport.hasCharacteristic(id, SonyBluetoothConstants.CAMERA_CONTROL_UUID)
        if (!wanted) {
            if (keepAwakeJobs.remove(id)?.also { it.cancel() } != null) {
                log.i { "No longer keeping $id awake" }
            }
            return
        }
        if (keepAwakeJobs[id]?.isActive == true) return
        log.i { "Keeping $id awake while it is connected" }
        keepAwakeJobs[id] = scope.launch {
            while (true) {
                val current = registry.get(id)
                if (current == null || current.cameraOff) break
                // A setup restarted meanwhile (the camera changed its services) only pauses it.
                if (current.isLocationReady) {
                    port.writeCharacteristic(
                        id,
                        SonyBluetoothConstants.CAMERA_CONTROL_UUID,
                        SonyBluetoothConstants.KEEP_AWAKE_COMMAND,
                    )
                }
                delay(SonyBluetoothConstants.KEEP_AWAKE_INTERVAL_MS.milliseconds)
            }
        }
    }

    /** Switched-off Sony cameras still connected, and their setup retries. */
    private val sonyCameraOffRetries = mutableMapOf<String, Job>()

    /**
     * A Sony camera refused the GPS setup or a location with its "not available" error: it
     * is switched off, but "Cnct. while Power OFF" keeps it connected. It gets no location
     * (the phone's location stops if no other camera needs it) until it accepts the setup
     * again. Switching it on ends the connection, which starts a new setup; the retry only
     * covers a camera that stays connected.
     */
    private fun onSonyCameraOff(id: String) {
        if (isFujifilm(id) || registry.get(id)?.cameraOff != false) return
        log.i { "Camera $id is switched off (it refuses location); waiting for it to be switched on" }
        registry.updateIfPresent(id) { it.copy(cameraOff = true) }
        keepAwakeJobs.remove(id)?.cancel()
        sonyCameraOffRetries.remove(id)?.cancel()
        sonyCameraOffRetries[id] = scope.launch {
            while (true) {
                delay(SONY_OFF_RETRY_MS.milliseconds)
                val session = registry.get(id)
                if (session == null || !session.cameraOff || !transport.isConnected(id)) break
                log.d { "Checking whether $id was switched on" }
                sessionCoordinator.beginHandshake(id)
            }
        }
    }

    // ---- Fujifilm ----

    private fun isFujifilm(identifier: String) =
        registry.get(identifier)?.protocol == CameraProtocol.FujifilmSecure

    /** Devices whose setup restarted after a services change; see [handleTransportEvent]. */
    private val restartingSetup = mutableSetOf<String>()

    /** Bumped on every restart so an older setup run can tell it was replaced. */
    private val setupGeneration = mutableMapOf<String, Int>()

    /**
     * The camera changed its GATT services, so characteristics found so far are
     * stale (a Sony camera then rejects the GPS unlock and setup stalls). Throw
     * the running setup away and start again with a fresh discovery.
     */
    private fun handleServicesChanged(identifier: String) {
        val id = identifier.uppercase()
        if (registry.get(id) == null || !transport.isConnected(id)) return
        log.i { "Camera $id changed its Bluetooth services, restarting setup" }
        setupGeneration[id] = (setupGeneration[id] ?: 0) + 1
        restartingSetup += id
        queue.cancelOperations(id, "services changed")
        sessionCoordinator.clearSession(id)
        autoCorrection.clear(id)
        registry.updateIfPresent(id) {
            it.copy(
                phase = BleSessionPhase.DiscoveringServices,
                pairingRetryCount = 0,
                hasRetriedConfigRead = false,
            )
        }
        scope.launch { runDiscoveryAndHandshake(id, afterServicesChanged = true) }
    }

    /**
     * After a Service Changed indication Android re-reads the services itself and
     * silently drops discovery requests until it is done, without telling the app
     * when that is. Retry with short waits until a discovery completes.
     */
    private suspend fun rediscover(id: String, generation: Int?): BleOperationResult {
        repeat(REDISCOVERY_ATTEMPTS) { attempt ->
            delay(REDISCOVERY_PAUSE_MS.milliseconds)
            if (setupGeneration[id] != generation || !transport.isConnected(id)) {
                return BleOperationResult.Cancelled
            }
            val result = withTimeoutOrNull(REDISCOVERY_WAIT_MS.milliseconds) {
                queue.execute(id, BleOperation.DiscoverServices)
            }
            if (result is BleOperationResult.Success) return result
            log.d { "Rediscovery of $id not answered (attempt ${attempt + 1}), retrying" }
            // Drop the unanswered request so the next one isn't queued behind it.
            queue.cancelOperations(id, "rediscovery retry")
        }
        return BleOperationResult.Timeout
    }

    private suspend fun runFujifilmHandshake(id: String) {
        log.i { "Camera $id speaks the Fujifilm secure protocol" }
        val generation = setupGeneration[id]
        registry.updateIfPresent(id) {
            it.copy(protocol = CameraProtocol.FujifilmSecure, phase = BleSessionPhase.EnablingGps)
        }
        val intervalS = loadFujifilmIntervals(id)
        val result = fujifilm.runHandshake(id, intervalS)
        // A restarted setup (services changed) reports its own result.
        if (setupGeneration[id] != generation) return
        when (result) {
            FujifilmHandshakeResult.Success -> {
                // Like Fujifilm's app: set the clock before answering location requests.
                // The camera applies it only if it asked (NOT1 `01 00`, sent on the first
                // connection after it is switched on or wakes, right after NOT1 is
                // subscribed); a time packet it didn't ask for is accepted and ignored.
                syncFujifilmTime(id)
                // Before the camera counts as ready: with location sync off it takes no
                // location, so it must not show as receiving one or start the GPS.
                autoCorrection.readDuringSetup(id)
                updateFujifilmPowerState(id)
                if (setupGeneration[id] != generation) return
                if (registry.get(id)?.cameraResponding == false) watchFujifilmSilence(id)
                handleHandshakeComplete(id)
                storeCameraName(id)
            }
            FujifilmHandshakeResult.PairingRejected -> {
                log.e { "Pairing retries exhausted for $id" }
                registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Error) }
                _events.tryEmit(OrchestratorEvent.PairingFailed(id))
            }

            is FujifilmHandshakeResult.Failed -> {
                // Nothing to do if the camera disconnected meanwhile.
                if (registry.get(id) == null) return
                log.e { "Fujifilm handshake failed for $id at ${result.step}" }
                registry.updateIfPresent(id) { it.copy(phase = BleSessionPhase.Error) }
            }
        }
    }

    /**
     * Saves the camera's own name (e.g. "X100VI-" plus four serial characters, from
     * NOT4) for the camera list, unless the user renamed the camera.
     */
    private suspend fun storeCameraName(id: String) {
        val name = fujifilm.readCameraName(id) ?: return
        runCatching {
            val device = deviceDao.getAllCameraDevices()
                .firstOrNull { it.mac.equals(id, ignoreCase = true) } ?: return
            if (device.deviceNameIsCustom || device.deviceName == name) return
            deviceDao.setDeviceName(id, name, isCustom = false)
            log.i { "Camera $id reports the name $name" }
        }.onFailure { log.w(it, msg = { "Could not store the name of $id" }) }
    }

    /** When each Fujifilm camera last asked for the time; see [syncFujifilmTime]. */
    private val lastFujifilmTimeRequest = mutableMapOf<String, TimeMark>()

    /** See [watchFujifilmSilence]. */
    private val fujifilmWatchdogs = mutableMapOf<String, Job>()

    private fun forgetFujifilmConnection(id: String) {
        lastFujifilmTimeRequest -= id
        fujifilmWatchdogs.remove(id)?.cancel()
    }

    /**
     * A working Fujifilm camera notifies within about a second of setup. One that stays
     * silent ignores the phone: it asks for no location and doesn't apply the time
     * (seen on the X100VI, for minutes, after quick reconnects and at power-on); a new
     * connection brought it back. First repeat the setup on the same link, then reconnect.
     */
    private fun watchFujifilmSilence(id: String) {
        fujifilmWatchdogs.remove(id)?.cancel()
        fujifilmWatchdogs[id] = scope.launch {
            delay(FUJIFILM_SILENCE_TIMEOUT)
            if (!isSilentFujifilm(id)) return@launch
            log.w { "Fujifilm camera $id stays silent after setup, repeating the setup" }
            val intervalS = registry.get(id)?.locationIntervalS ?: FujifilmBluetoothConstants.GEOTAG_SYNC_INTERVAL_SECONDS
            if (fujifilm.runHandshake(id, intervalS) == FujifilmHandshakeResult.Success) syncFujifilmTime(id)
            delay(FUJIFILM_SILENCE_TIMEOUT)
            if (!isSilentFujifilm(id)) return@launch
            log.w { "Fujifilm camera $id is still silent, reconnecting" }
            if (!transport.reconnect(id)) log.w { "Could not reconnect to $id" }
        }
    }

    private fun isSilentFujifilm(id: String): Boolean {
        val session = registry.get(id) ?: return false
        return transport.isConnected(id) && session.protocol == CameraProtocol.FujifilmSecure &&
                session.phase == BleSessionPhase.Transmitting && !session.cameraResponding
    }

    private fun handleFujifilmNotification(event: BleTransportEvent.CharacteristicChanged) {
        val id = event.identifier.uppercase()
        val notification = fujifilm.onCharacteristicChanged(id, event.characteristicUuid, event.value)
        if (registry.get(id)?.cameraResponding == false) {
            registry.updateIfPresent(id) { it.copy(cameraResponding = true) }
            fujifilmWatchdogs.remove(id)?.cancel()
        }
        when (notification) {
            FujifilmNotification.GeotagRequested -> {
                locationManager.onLocationRequested(id)
                // Switching the camera off or on usually reconnects it, but not always.
                scope.launch { updateFujifilmPowerState(id) }
            }

            FujifilmNotification.DateSyncRequested -> {
                if (FujifilmPacketBuilder.isConfigured(event.value)) {
                    log.i { "Fujifilm camera $id reports it is configured" }
                }
                // During setup the handshake sets the clock itself.
                if (registry.get(id)?.phase == BleSessionPhase.Transmitting) {
                    scope.launch { syncFujifilmTime(id, onRequest = true) }
                }
            }

            FujifilmNotification.LocationSyncSettingChanged -> autoCorrection.onNotified(
                id, CameraAutoCorrectionSetting.FujifilmLocationSync, event.value,
            )

            FujifilmNotification.Other -> Unit
        }
    }

    /** Reads the camera's intervals into its session; returns the location request interval. */
    private suspend fun loadFujifilmIntervals(id: String): Int {
        val intervalS = runCatching { deviceDao.findLocationIntervalS(id) }.getOrNull()
            ?.takeIf { it in FujifilmBluetoothConstants.GEOTAG_SYNC_INTERVALS_SECONDS }
            ?: FujifilmBluetoothConstants.GEOTAG_SYNC_INTERVAL_SECONDS
        val standbyS = runCatching { deviceDao.findStandbyIntervalS(id) }.getOrNull()
            ?.takeIf { it in FujifilmBluetoothConstants.STANDBY_INTERVALS_SECONDS }
            ?: FujifilmBluetoothConstants.STANDBY_INTERVAL_SECONDS
        registry.updateIfPresent(id) { it.copy(locationIntervalS = intervalS, standbyIntervalS = standbyS) }
        return intervalS
    }

    /**
     * Whether the camera is in standby (switched off or asleep, still connected): shown as
     * "Standby", still answered, and the phone's location slows down if no other camera
     * needs it.
     */
    private suspend fun updateFujifilmPowerState(id: String) {
        val value = fujifilm.readPowerSwitch(id) ?: return
        val hex = value.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val awake = FujifilmPacketBuilder.isAwake(value)
        if (awake == null) {
            log.d { "Fujifilm camera $id reports an unknown power switch value $hex" }
            return
        }
        val session = registry.get(id) ?: return
        if (session.inStandby == !awake) return
        log.i { "Fujifilm camera $id is ${if (awake) "awake" else "in standby"} (power switch $hex)" }
        registry.updateIfPresent(id) { it.copy(inStandby = !awake) }
    }

    /**
     * Sets a Fujifilm camera's date, time and time zone unless the user turned that off
     * for the camera. [onRequest]: the camera asked (NOT1), which Fujifilm's app answers
     * every time; answered at most every [FUJIFILM_TIME_REQUEST_MIN_INTERVAL] so a
     * camera that reports each new time can't cause a loop.
     */
    private suspend fun syncFujifilmTime(id: String, onRequest: Boolean = false) {
        if (runCatching { deviceDao.findTimeSyncEnabled(id) }.getOrNull() == false) return
        if (onRequest) {
            val last = lastFujifilmTimeRequest[id]
            if (last != null && last.elapsedNow() < FUJIFILM_TIME_REQUEST_MIN_INTERVAL) {
                log.d { "Camera $id asked for the time again right away, skipping" }
                return
            }
            lastFujifilmTimeRequest[id] = TimeSource.Monotonic.markNow()
        }
        fujifilm.syncTime(id)
    }
}

private val FUJIFILM_TIME_REQUEST_MIN_INTERVAL = 10.seconds
private val FUJIFILM_SILENCE_TIMEOUT = 15.seconds

// Rediscovery after a services change: up to 8 × (1 s pause + 2.5 s wait) ≈ 28 s.
// Android's own re-read of a camera's services took up to about 7 s in tests.
/** Sony: how often a connected, switched-off camera is asked for the GPS setup again. */
private const val SONY_OFF_RETRY_MS = 30_000L

/** Sony writes a switched-off camera refuses: GPS unlock and lock, and the location. */
private val SONY_LOCATION_OPERATIONS = setOf(
    SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND,
    SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND,
    SonyBluetoothConstants.CHARACTERISTIC_UUID,
).map { it.lowercase() }.toSet()

private const val REDISCOVERY_ATTEMPTS = 8
private const val REDISCOVERY_PAUSE_MS = 1_000L
private const val REDISCOVERY_WAIT_MS = 2_500L
