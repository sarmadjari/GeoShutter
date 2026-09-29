package com.sasch.cameragps.sharednew.bluetooth.transport

import kotlinx.coroutines.flow.Flow

/**
 * Outcome of a single BLE I/O operation as reported by the platform stack.
 */
enum class BleOperationStatus {
    Success,
    Failure,

    /**
     * Authentication/encryption error (ATT error 5 or 15). On iOS this means the
     * device is not paired (or pairing was rejected); the orchestrator applies the
     * pairing retry policy. Effectively never fires on Android because devices are
     * bonded before connecting.
     */
    AuthError,
}

/**
 * Events emitted by a [BlePeripheralTransport].
 *
 * Identifiers are uppercase MAC addresses (Android) or peripheral UUID strings (iOS).
 * Platform callbacks may fire on any thread; events are delivered through a
 * Channel-backed flow and consumed by the single orchestrator collector.
 */
sealed interface BleTransportEvent {
    val identifier: String

    /** A peripheral connection was established (fresh connect or state restoration). */
    data class Connected(override val identifier: String) : BleTransportEvent

    /**
     * A peripheral disconnected. [statusCode] is the platform status when available
     * (Android GATT status), or `null` for synthetic disconnects (e.g. pause).
     */
    data class Disconnected(
        override val identifier: String,
        val statusCode: Int?,
    ) : BleTransportEvent

    /**
     * Service discovery finished.
     * Android: `onServicesDiscovered`. iOS: two-phase service/characteristic
     * discovery AND the pairing gate both completed.
     */
    data class ServicesDiscovered(
        override val identifier: String,
        val success: Boolean,
    ) : BleTransportEvent

    /**
     * The peripheral changed its GATT database (Service Changed indication).
     * Android: `onServiceChanged`. Characteristics found earlier are stale, so
     * discovery and setup must run again. Some Sony cameras send it right after
     * the link is encrypted.
     */
    data class ServicesChanged(override val identifier: String) : BleTransportEvent

    /** A characteristic read completed (read response). */
    data class CharacteristicRead(
        override val identifier: String,
        val characteristicUuid: String,
        val value: ByteArray,
        val status: BleOperationStatus,
    ) : BleTransportEvent

    /** A characteristic write completed. */
    data class CharacteristicWritten(
        override val identifier: String,
        val characteristicUuid: String,
        val status: BleOperationStatus,
        /** The ATT error of a failed write, when the platform reports one. */
        val attError: Int? = null,
    ) : BleTransportEvent

    /** A notification subscription change completed (Android: CCCD descriptor write). */
    data class SubscriptionChanged(
        override val identifier: String,
        val characteristicUuid: String,
        val enabled: Boolean,
        val status: BleOperationStatus,
    ) : BleTransportEvent

    /** Camera-initiated notification (e.g. remote status changes). */
    data class CharacteristicChanged(
        override val identifier: String,
        val characteristicUuid: String,
        val value: ByteArray,
    ) : BleTransportEvent
}

/**
 * Low-level, per-platform BLE contract. Implementations own the raw platform
 * objects (BluetoothGatt / CBPeripheral) and do nothing but initiate operations
 * and report their completion via [events].
 *
 * All orchestration (sequencing, retries, session state) lives above this
 * interface in common code — implementations must NOT chain operations
 * themselves. Exactly one operation per device is in flight at any time,
 * guaranteed by [com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationQueue].
 */
interface BlePeripheralTransport {

    /**
     * Channel-backed event stream. Single collector: the orchestrator.
     * Safe to feed from any thread.
     */
    val events: Flow<BleTransportEvent>

    /** Returns `true` if the device has an active connection. */
    fun isConnected(identifier: String): Boolean

    /** Returns `true` if a characteristic with [characteristicUuid] was discovered on the device. */
    fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean

    /** Whether the discovered characteristic advertises writes with a response. No BLE I/O. */
    fun supportsWriteWithResponse(identifier: String, characteristicUuid: String): Boolean

    /** Clear read/notification bookkeeping after success, timeout or cancellation. No BLE I/O. */
    fun finishRead(identifier: String, characteristicUuid: String) = Unit

    // ---- Operation initiation. Completion arrives via [events]. ----
    // Returning false means the operation could not even be started
    // (no connection, unknown characteristic, platform refusal).

    // A non-null serviceUuid limits the characteristic lookup to that service.

    fun initiateWrite(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
        serviceUuid: String? = null,
    ): Boolean

    fun initiateRead(identifier: String, characteristicUuid: String, serviceUuid: String? = null): Boolean

    /**
     * Enable or disable notifications, or indications when [indication] is set or
     * the characteristic only supports indications.
     */
    fun initiateSubscribe(
        identifier: String,
        characteristicUuid: String,
        enable: Boolean,
        indication: Boolean = false,
        serviceUuid: String? = null,
    ): Boolean

    /**
     * Android: `gatt.discoverServices()`.
     * iOS: two-phase service/characteristic discovery followed by the pairing gate.
     */
    fun initiateDiscoverServices(identifier: String): Boolean

    /**
     * Drops the connection and connects again right away. The session ends with a
     * [BleTransportEvent.Disconnected]. False when unsupported or the device is unknown.
     */
    fun reconnect(identifier: String): Boolean = false
}
