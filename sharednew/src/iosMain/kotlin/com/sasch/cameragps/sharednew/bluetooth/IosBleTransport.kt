package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.session.PairingRetryPolicy
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import com.sasch.cameragps.sharednew.bluetooth.transport.BleUuids
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBATTErrorDomain
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS implementation of [BlePeripheralTransport] over CoreBluetooth.
 *
 * Owns the peripheral delegate, the per-peripheral characteristic cache and
 * the two-phase (services → characteristics) discovery including the pairing
 * gate: the first operation that needs encryption makes iOS pair before the
 * setup starts. Everything above (sequencing, retries, session state) lives in
 * the shared orchestrator.
 *
 * [IosBluetoothController] owns the central manager and calls
 * [attachPeripheral]/[detachPeripheral] from its central delegate.
 *
 * Main-thread only (CoreBluetooth contract; the central uses the main queue).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosBleTransport(
    private val scope: CoroutineScope,
    private val pairingPolicy: PairingRetryPolicy = PairingRetryPolicy(),
    /** Pairing gate retries exhausted — the controller should cancel the connection. */
    private val onPairingGateExhausted: (CBPeripheral) -> Unit,
    /**
     * Drop the connection so the central connects again ([reconnect]). The controller
     * cancels it; its disconnect handling issues the new pending connect.
     */
    private val onReconnectRequested: (CBPeripheral) -> Unit = {},
) : BlePeripheralTransport {

    private val log = logging()

    private val eventChannel = Channel<BleTransportEvent>(Channel.UNLIMITED)
    override val events: Flow<BleTransportEvent> = eventChannel.receiveAsFlow()

    private class DiscoveryState {
        /** Set by the first services callback; later ones belong to an earlier discovery. */
        var servicesDiscovered = false

        /**
         * Services whose characteristics are still outstanding, compared by identity so
         * late callbacks of an earlier discovery are not counted.
         */
        val pendingServices = mutableListOf<CBService>()
        var gating = false
        var gateRetryCount = 0
        var gateCharacteristic: CBCharacteristic? = null

        /** Fujifilm: the gate reads the status instead of subscribing. */
        var gateByRead = false
    }

    private class DiscoveredCharacteristic(
        /** Normalized ([BleUuids.normalize]) service UUID. */
        val serviceUuid: String,
        /** Normalized ([BleUuids.normalize]) characteristic UUID. */
        val uuid: String,
        val characteristic: CBCharacteristic,
    )

    private class PeripheralHandle(val peripheral: CBPeripheral) {
        /** In discovery order; a Fujifilm camera has one UUID in two services. */
        val characteristics = mutableListOf<DiscoveredCharacteristic>()
        val notifiableCharacteristics = mutableListOf<CBCharacteristic>()
        var discovery: DiscoveryState? = null
        var connectedAnnounced = false

        /** Normalized UUIDs of the reads the queue is waiting for. */
        val pendingReads = mutableSetOf<String>()

        /** Pairing gate reads not answered yet; their values are never notifications. */
        val gateReads = mutableListOf<CBCharacteristic>()

        /** The characteristic, in [serviceUuid] when given, else in the first service that has it. */
        fun find(characteristicUuid: String, serviceUuid: String? = null): CBCharacteristic? {
            val uuid = BleUuids.normalize(characteristicUuid)
            val service = serviceUuid?.let(BleUuids::normalize)
            return characteristics.firstOrNull {
                it.uuid == uuid && (service == null || it.serviceUuid == service)
            }?.characteristic
        }
    }

    /** Uppercased peripheral UUID string → handle. */
    private val handles = mutableMapOf<String, PeripheralHandle>()

    // ---------------------------------------------------------------------------
    // API for IosBluetoothController's central delegate
    // ---------------------------------------------------------------------------

    /**
     * Register a connected peripheral (fresh connect or state restoration) and
     * announce it to the orchestrator. Idempotent.
     */
    fun attachPeripheral(peripheral: CBPeripheral) {
        val handle = handleFor(peripheral)
        if (!handle.connectedAnnounced) {
            handle.connectedAnnounced = true
            eventChannel.trySend(BleTransportEvent.Connected(identifierOf(peripheral)))
        }
    }

    /** Register a known-but-not-yet-connected peripheral (restore path) so delegate callbacks are not lost. */
    fun registerPeripheral(peripheral: CBPeripheral) {
        handleFor(peripheral)
    }

    private fun handleFor(peripheral: CBPeripheral): PeripheralHandle {
        val id = identifierOf(peripheral)
        peripheral.delegate = peripheralDelegate
        return handles.getOrPut(id) { PeripheralHandle(peripheral) }
    }

    /** Drop a peripheral and announce the disconnect to the orchestrator. */
    fun detachPeripheral(identifier: String) {
        val id = identifier.uppercase()
        if (handles.remove(id) != null) {
            eventChannel.trySend(BleTransportEvent.Disconnected(id, statusCode = null))
        }
    }

    /** Silently drop all peripherals (force shutdown — the caller already tore state down). */
    fun detachAll() {
        handles.clear()
    }

    // ---------------------------------------------------------------------------
    // BlePeripheralTransport
    // ---------------------------------------------------------------------------

    override fun isConnected(identifier: String): Boolean =
        handles[identifier.uppercase()]?.peripheral?.state == CBPeripheralStateConnected

    override fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean =
        handles[identifier.uppercase()]?.find(characteristicUuid) != null

    override fun supportsWriteWithResponse(
        identifier: String,
        characteristicUuid: String
    ): Boolean {
        val characteristic = handles[identifier.uppercase()]
            ?.find(characteristicUuid) ?: return false
        return characteristic.properties and CBCharacteristicPropertyWrite != 0uL
    }

    override fun finishRead(identifier: String, characteristicUuid: String) {
        handles[identifier.uppercase()]?.pendingReads?.remove(BleUuids.normalize(characteristicUuid))
    }

    override fun finishDiscovery(identifier: String) {
        val handle = handles[identifier.uppercase()] ?: return
        if (handle.discovery != null) {
            // Timed out or cancelled: stop the gate, so it neither prompts again nor
            // reports a late result nobody waits for.
            log.w { "Discovery of ${identifier.uppercase()} ended before it completed, stopping it" }
            handle.discovery = null
        }
    }

    // CoreBluetooth picks notifications or indications itself, so `indication` is not
    // needed here.
    override fun initiateWrite(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
        serviceUuid: String?,
    ): Boolean {
        val handle = handles[identifier.uppercase()] ?: return false
        val characteristic = handle.find(characteristicUuid, serviceUuid) ?: return false
        handle.peripheral.writeValue(
            data = value.toNSData(),
            forCharacteristic = characteristic,
            type = CBCharacteristicWriteWithResponse,
        )
        return true
    }

    override fun initiateRead(identifier: String, characteristicUuid: String, serviceUuid: String?): Boolean {
        val handle = handles[identifier.uppercase()] ?: return false
        val characteristic = handle.find(characteristicUuid, serviceUuid) ?: return false
        handle.pendingReads.add(BleUuids.normalize(characteristicUuid))
        handle.peripheral.readValueForCharacteristic(characteristic)
        return true
    }

    override fun initiateSubscribe(
        identifier: String,
        characteristicUuid: String,
        enable: Boolean,
        indication: Boolean,
        serviceUuid: String?,
    ): Boolean {
        val id = identifier.uppercase()
        val handle = handles[id] ?: return false
        val characteristic = handle.find(characteristicUuid, serviceUuid) ?: return false
        if (characteristic.isNotifying == enable) {
            // Already in the requested state — complete the queued operation right away
            eventChannel.trySend(
                BleTransportEvent.SubscriptionChanged(
                    id, sharedUuidFor(characteristic), enable, BleOperationStatus.Success,
                )
            )
            return true
        }
        handle.peripheral.setNotifyValue(enable, forCharacteristic = characteristic)
        return true
    }

    override fun initiateDiscoverServices(identifier: String): Boolean {
        val handle = handles[identifier.uppercase()] ?: return false
        handle.characteristics.clear()
        handle.notifiableCharacteristics.clear()
        handle.discovery = DiscoveryState()
        log.d { "Initiating service discovery for ${identifier.uppercase()}" }
        handle.peripheral.discoverServices(DISCOVERY_SERVICE_UUIDS)
        return true
    }

    override fun reconnect(identifier: String): Boolean {
        val handle = handles[identifier.uppercase()] ?: return false
        if (handle.peripheral.state != CBPeripheralStateConnected) return false
        log.i { "Reconnecting ${identifier.uppercase()}" }
        onReconnectRequested(handle.peripheral)
        return true
    }

    // ---------------------------------------------------------------------------
    // Peripheral delegate
    // ---------------------------------------------------------------------------

    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val id = identifierOf(peripheral)
            val handle = handles[id] ?: return
            val discovery = handle.discovery ?: return
            if (discovery.servicesDiscovered) {
                log.d { "Ignoring a services callback of an earlier discovery for $id" }
                return
            }
            discovery.servicesDiscovered = true

            val services = peripheral.services.orEmpty().filterIsInstance<CBService>()
            log.d { "Discovered ${services.size} services for $id" }
            if (didDiscoverServices != null || services.isEmpty()) {
                log.e { "Service discovery failed for $id: ${didDiscoverServices?.localizedDescription ?: "no services"}" }
                handle.discovery = null
                eventChannel.trySend(BleTransportEvent.ServicesDiscovered(id, success = false))
                return
            }

            discovery.pendingServices.addAll(services)
            services.forEach { service ->
                peripheral.discoverCharacteristics(
                    characteristicUUIDs = null,
                    forService = service,
                )
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val id = identifierOf(peripheral)
            val handle = handles[id] ?: return
            val discovery = handle.discovery ?: return
            val service = didDiscoverCharacteristicsForService
            if (!discovery.pendingServices.removeAll { it == service }) return
            if (error != null) {
                log.w { "Characteristic discovery failed for ${service.UUID.UUIDString} on $id: ${error.localizedDescription}" }
            }

            val serviceUuid = BleUuids.normalize(service.UUID.UUIDString)
            service.characteristics?.forEach { characteristicAny ->
                val characteristic = characteristicAny as CBCharacteristic
                log.v {
                    "  Discovered characteristic ${characteristic.UUID}  props=0x${
                        characteristic.properties.toString(16)
                    }"
                }
                handle.characteristics += DiscoveredCharacteristic(
                    serviceUuid = serviceUuid,
                    uuid = BleUuids.normalize(characteristic.UUID.UUIDString),
                    characteristic = characteristic,
                )

                val supportsNotify =
                    (characteristic.properties and CBCharacteristicPropertyNotify) != 0uL ||
                        (characteristic.properties and CBCharacteristicPropertyIndicate) != 0uL
                if (supportsNotify) {
                    handle.notifiableCharacteristics.add(characteristic)
                }
            }

            if (discovery.pendingServices.isEmpty()) {
                startPairingGate(id, handle, discovery)
            }
        }

        /**
         * The camera changed its GATT database (Service Changed): characteristics of the
         * invalidated services are stale, so the orchestrator discovers and sets up again.
         */
        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didModifyServices: List<*>) {
            val id = identifierOf(peripheral)
            if (handles[id] == null) return
            val invalidated = didModifyServices.filterIsInstance<CBService>()
                .map { BleUuids.normalize(it.UUID.UUIDString) }
            log.i { "Services changed on $id: $invalidated" }
            if (invalidated.any { it in DISCOVERED_SERVICES }) {
                eventChannel.trySend(BleTransportEvent.ServicesChanged(id))
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val id = identifierOf(peripheral)
            val handle = handles[id] ?: return
            val characteristic = didUpdateValueForCharacteristic
            val sharedUuid = sharedUuidFor(characteristic)
            log.v { "Value update for $sharedUuid (error=${error?.code} / ${error?.localizedDescription})" }

            val gateReadIndex = handle.gateReads.indexOfFirst { it == characteristic }
            if (gateReadIndex >= 0) {
                handle.gateReads.removeAt(gateReadIndex)
                val discovery = handle.discovery
                if (discovery != null && discovery.gating && discovery.gateByRead &&
                    discovery.gateCharacteristic == characteristic
                ) {
                    handlePairingGateResult(id, handle, discovery, characteristic, error)
                } else {
                    log.d { "Ignoring the answer to an earlier pairing gate read on $id" }
                }
                return
            }

            // CoreBluetooth shares this callback between reads and notifications.
            // Track requested reads, including the DD32/DD33 camera settings.
            val isReadResponse = handle.pendingReads.remove(BleUuids.normalize(sharedUuid))
            if (isReadResponse) {
                if (error != null) {
                    log.i { "BLE read failed for $sharedUuid: ${error.domain} ${error.code} ${error.localizedDescription}" }
                }
                eventChannel.trySend(
                    BleTransportEvent.CharacteristicRead(
                        id,
                        sharedUuid,
                        characteristic.value?.toByteArray() ?: ByteArray(0),
                        statusOf(error),
                    )
                )
                return
            }

            if (error != null) {
                log.e { "Notification error for $sharedUuid: ${error.localizedDescription}" }
                return
            }
            val value = characteristic.value?.toByteArray() ?: return
            eventChannel.trySend(BleTransportEvent.CharacteristicChanged(id, sharedUuid, value))
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val id = identifierOf(peripheral)
            if (handles[id] == null) return
            val sharedUuid = sharedUuidFor(didWriteValueForCharacteristic)
            log.v { "Write for $sharedUuid completed (error=${error?.code} / ${error?.localizedDescription})" }

            val isRemoteRefusal =
                sharedUuid == SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID &&
                        error?.domain == CBATTErrorDomain &&
                        error.code == 144L

            if (error != null && !isAuthenticationError(error) && !isRemoteRefusal) {
                log.e { "BLE write failed for $sharedUuid: ${error.localizedDescription} ${error.code}" }
            }
            eventChannel.trySend(
                BleTransportEvent.CharacteristicWritten(
                    id, sharedUuid, statusOf(error),
                    attError = error?.takeIf { it.domain == CBATTErrorDomain }?.code?.toInt(),
                )
            )
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val id = identifierOf(peripheral)
            val handle = handles[id] ?: return
            val discovery = handle.discovery

            if (discovery?.gating == true && !discovery.gateByRead &&
                discovery.gateCharacteristic == didUpdateNotificationStateForCharacteristic
            ) {
                handlePairingGateResult(
                    id, handle, discovery,
                    didUpdateNotificationStateForCharacteristic, error,
                )
                return
            }

            eventChannel.trySend(
                BleTransportEvent.SubscriptionChanged(
                    id,
                    sharedUuidFor(didUpdateNotificationStateForCharacteristic),
                    didUpdateNotificationStateForCharacteristic.isNotifying,
                    statusOf(error),
                )
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Pairing gate
    // ---------------------------------------------------------------------------

    /**
     * The first operation that needs encryption makes iOS pair with the camera
     * before the setup starts. Auth errors are retried per [pairingPolicy].
     *
     * Sony: subscribing to a notifiable characteristic; on success the gate
     * subscription is removed again. Fujifilm: reading the status, the first step of
     * its setup anyway. Subscribing first would change the order the camera expects,
     * and a camera being added must be set up on the connection that pairs it (its
     * AccessorySetupKit item leaves pairing to the app for that reason).
     */
    private fun startPairingGate(id: String, handle: PeripheralHandle, discovery: DiscoveryState) {
        val fujifilmStatus = handle.find(
            FujifilmBluetoothConstants.STATUS_CHARACTERISTIC_UUID,
            FujifilmBluetoothConstants.PAIR_SERVICE_UUID,
        )
        if (fujifilmStatus != null) {
            discovery.gating = true
            discovery.gateByRead = true
            discovery.gateCharacteristic = fujifilmStatus
            log.d { "Reading the Fujifilm status to trigger pairing" }
            readForGate(handle, fujifilmStatus)
            return
        }
        val target = handle.notifiableCharacteristics.firstOrNull()
        if (target == null) {
            log.d { "No notifiable characteristic – proceeding without explicit pairing" }
            handle.discovery = null
            eventChannel.trySend(BleTransportEvent.ServicesDiscovered(id, success = true))
            return
        }
        discovery.gating = true
        discovery.gateCharacteristic = target
        log.d { "Subscribing to notifications on ${target.UUID.UUIDString} to trigger pairing" }
        handle.peripheral.setNotifyValue(true, forCharacteristic = target)
    }

    private fun readForGate(handle: PeripheralHandle, characteristic: CBCharacteristic) {
        handle.gateReads += characteristic
        handle.peripheral.readValueForCharacteristic(characteristic)
    }

    private fun handlePairingGateResult(
        id: String,
        handle: PeripheralHandle,
        discovery: DiscoveryState,
        characteristic: CBCharacteristic,
        error: NSError?,
    ) {
        if (isAuthenticationError(error)) {
            discovery.gateRetryCount++
            // Each Fujifilm attempt is a pairing request the person has to confirm.
            val maxRetries =
                if (discovery.gateByRead) FUJIFILM_GATE_RETRIES else pairingPolicy.maxRetries
            if (discovery.gateRetryCount > maxRetries) {
                log.e { "Pairing failed after $maxRetries retries, disconnecting" }
                handle.discovery = null
                onPairingGateExhausted(handle.peripheral)
                eventChannel.trySend(BleTransportEvent.ServicesDiscovered(id, success = false))
                return
            }
            log.d {
                "Auth error – retrying pairing in ${pairingPolicy.retryDelayMs}ms " +
                    "(attempt ${discovery.gateRetryCount}/$maxRetries)"
            }
            scope.launch {
                delay(pairingPolicy.retryDelayMs)
                if (handle.discovery === discovery) {
                    if (discovery.gateByRead) {
                        readForGate(handle, characteristic)
                    } else {
                        handle.peripheral.setNotifyValue(true, forCharacteristic = characteristic)
                    }
                }
            }
            return
        }

        if (error != null) {
            log.d { "Pairing gate operation failed (non-auth): ${error.localizedDescription} – continuing" }
        } else {
            log.d { "Pairing gate operation succeeded – device is paired" }
            if (!discovery.gateByRead) {
                handle.peripheral.setNotifyValue(false, forCharacteristic = characteristic)
            }
        }
        handle.discovery = null
        eventChannel.trySend(BleTransportEvent.ServicesDiscovered(id, success = true))
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun identifierOf(peripheral: CBPeripheral): String =
        peripheral.identifier.UUIDString.uppercase()

    private fun statusOf(error: NSError?): BleOperationStatus = when {
        error == null -> BleOperationStatus.Success
        isAuthenticationError(error) -> BleOperationStatus.AuthError
        else -> BleOperationStatus.Failure
    }

    private fun isAuthenticationError(error: NSError?): Boolean {
        if (error == null) return false
        return error.code == SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_AUTHENTICATION.toLong() ||
            error.code == SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_ENCRYPTION.toLong()
    }

    /**
     * Map a CoreBluetooth characteristic back to the shared UUID string: the Sony
     * constant it always was, otherwise the normalized UUID (as Android reports it).
     */
    private fun sharedUuidFor(characteristic: CBCharacteristic): String {
        val normalized = BleUuids.normalize(characteristic.UUID.UUIDString)
        return sonyCharacteristicUuids[normalized] ?: normalized
    }

    private companion object {
        /**
         * Retries of the Fujifilm pairing gate. Keeps the gate (two pairing requests, up
         * to 30 s each) within the controller's discovery timeout.
         */
        const val FUJIFILM_GATE_RETRIES = 1

        /** Services discovered on every connection: Sony's, then Fujifilm's. */
        val DISCOVERED_SERVICES: List<String> = listOf(
            SonyBluetoothConstants.SERVICE_UUID,
            SonyBluetoothConstants.CONTROL_SERVICE_UUID,
            SonyBluetoothConstants.REMOTE_SERVICE_UUID,
        ).plus(FujifilmBluetoothConstants.SERVICE_UUIDS).map(BleUuids::normalize)

        val DISCOVERY_SERVICE_UUIDS: List<CBUUID> =
            DISCOVERED_SERVICES.map { CBUUID.UUIDWithString(it) }

        /** Normalized UUID → Sony constant, built once. */
        val sonyCharacteristicUuids: Map<String, String> = listOf(
            SonyBluetoothConstants.CHARACTERISTIC_UUID,
            SonyBluetoothConstants.CHARACTERISTIC_READ_UUID,
            SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND,
            SonyBluetoothConstants.CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND,
            SonyBluetoothConstants.CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA,
            SonyBluetoothConstants.TIME_SYNC_CHARACTERISTIC_UUID,
            SonyBluetoothConstants.CAMERA_CONTROL_UUID,
            SonyBluetoothConstants.AUTO_TIME_CORRECTION_UUID,
            SonyBluetoothConstants.AUTO_AREA_ADJUSTMENT_UUID,
            SonyBluetoothConstants.REMOTE_CHARACTERISTIC_UUID,
            SonyBluetoothConstants.REMOTE_STATUS_UUID,
        ).associateBy(BleUuids::normalize)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)

    return ByteArray(size).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}
