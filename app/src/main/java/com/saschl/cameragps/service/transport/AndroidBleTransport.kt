package com.saschl.cameragps.service.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import java.util.Collections
import java.util.UUID

/**
 * Android implementation of [BlePeripheralTransport] over BluetoothGatt.
 *
 * Owns the GATT connection registry (bond check + `autoConnect = true`,
 * absorbed from the former `CameraConnectionManager`) and the GATT callback;
 * all sequencing/orchestration lives in the shared layer. GATT callbacks
 * arrive on binder threads and only feed the event channel — they never touch
 * shared state directly.
 *
 * A disconnect keeps the GATT handle open so `autoConnect` can resume the
 * connection when the camera reappears; [disconnectAll] is the only close path.
 */
class AndroidBleTransport(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
) : BlePeripheralTransport {

    private companion object {
        // BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED is hidden from the public SDK.
        const val ERROR_DEVICE_NOT_CONNECTED = 4
    }

    private val eventChannel = Channel<BleTransportEvent>(Channel.UNLIMITED)
    override val events: Flow<BleTransportEvent> = eventChannel.receiveAsFlow()

    private class Connection(val gatt: BluetoothGatt, val direct: Boolean = false) {
        @Volatile
        var isActive = false
    }

    /** Uppercased MAC → connection. Entries survive disconnects (autoConnect). */
    private val connections = Collections.synchronizedMap(mutableMapOf<String, Connection>())

    /** Last requested enable state per "MAC:UUID", to label SubscriptionChanged events. */
    private val pendingSubscribeEnable =
        Collections.synchronizedMap(mutableMapOf<String, Boolean>())

    /**
     * Opens a background (`autoConnect`) connection, or with [direct] a direct one
     * that scans aggressively for about 30 s. Direct is for cameras that advertise
     * only briefly (Fujifilm); after a failed attempt or its first disconnect the
     * device falls back to the background connection. A direct request also
     * replaces a background connection that is still waiting.
     */
    fun connect(mac: String, direct: Boolean = false): Boolean {
        val address = mac.uppercase()
        val existing = connections[address]
        if (existing != null) {
            if (!direct || existing.direct || existing.isActive) return true
            if (!connections.remove(address, existing)) return true
            closeQuietly(existing.gatt)
        }

        try {
            val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                //Timber.w("Device $address is not paired. Cannot connect.")
                return false
            }
            val gatt = openGatt(device, autoConnect = !direct)
                ?: throw IllegalStateException("Failed to connect to device $address: GATT is null")
            connections[address] = Connection(gatt, direct)
        } catch (e: SecurityException) {
            Timber.e("SecurityException while connecting to device $address: ${e.message}")
            return false
        }

        return true
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice, autoConnect: Boolean): BluetoothGatt? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            device.connectGatt(
                BluetoothGattConnectionSettings.Builder()
                    .setAutoConnectEnabled(autoConnect)
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .setAutomaticMtuEnabled(true)
                    .build(),
                context.mainExecutor,
                gattCallback
            )
        } else if (autoConnect) {
            device.connectGatt(context, true, gattCallback)
        } else {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

    /** A direct connection does not come back by itself: hand the device to autoConnect. */
    private fun fallBackToAutoConnect(address: String, finished: Connection) {
        // Not found: shut down or replaced meanwhile.
        if (!connections.remove(address, finished)) return
        closeQuietly(finished.gatt)
        val gatt = runCatching { openGatt(finished.gatt.device, autoConnect = true) }
            .onFailure { Timber.w(it, "Could not reopen %s with autoConnect", address) }
            .getOrNull() ?: return
        if (connections.putIfAbsent(address, Connection(gatt)) != null) closeQuietly(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun closeQuietly(gatt: BluetoothGatt) {
        runCatching {
            try {
                gatt.disconnect()
            } finally {
                gatt.close()
            }
        }.onFailure { Timber.w(it) }
    }

    /** `true` if a GATT handle exists for this device (connected or waiting for autoConnect). */
    fun hasConnection(mac: String): Boolean = connections.containsKey(mac.uppercase())

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectAll() {
        val snapshot: List<Connection> = synchronized(connections) {
            connections.values.toList().also { connections.clear() }
        }
        snapshot.forEach { connection ->
            runCatching {
                try {
                    connection.gatt.disconnect()
                } finally {
                    connection.gatt.close()
                }
            }.onFailure { Timber.w(it) }
        }
    }

    /**
     * Mark a device inactive without closing the GATT (autoConnect keeps
     * listening for the camera). Emits a synthetic Disconnected event so the
     * orchestrator clears the session.
     */
    fun pauseDevice(mac: String) {
        val address = mac.uppercase()
        val connection = connections[address] ?: return
        if (connection.isActive) {
            connection.isActive = false
            eventChannel.trySend(BleTransportEvent.Disconnected(address, statusCode = null))
        }
    }

    /** Number of devices with a live (resumed) connection. */
    fun connectedCount(): Int = synchronized(connections) {
        connections.values.count { it.isActive }
    }

    /**
     * Drop back to the default connection parameters once the handshake is done —
     * the periodic location writes do not need the short interval, and holding it
     * costs battery on both the phone and the camera.
     */
    @SuppressLint("MissingPermission")
    fun relaxConnection(mac: String) {
        val connection = connections[mac.uppercase()] ?: return
        if (!connection.isActive) return
        if (!connection.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)) {
            Timber.w("Could not relax connection priority for %s", mac)
        }
    }

    // ---------------------------------------------------------------------------
    // BlePeripheralTransport
    // ---------------------------------------------------------------------------

    override fun isConnected(identifier: String): Boolean =
        connections[identifier.uppercase()]?.isActive == true

    override fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        return findCharacteristic(connection.gatt, characteristicUuid) != null
    }

    override fun supportsWriteWithResponse(
        identifier: String,
        characteristicUuid: String
    ): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return false
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    }

    override fun initiateWrite(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
        serviceUuid: String?,
    ): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        // The GATT handle survives disconnects for autoConnect; it is not proof of a live link.
        if (!connection.isActive) return false
        val characteristic =
            findCharacteristic(connection.gatt, characteristicUuid, serviceUuid) ?: return false
        return writeCharacteristicCompat(connection.gatt, characteristic, value)
    }

    @SuppressLint("MissingPermission")
    override fun initiateRead(
        identifier: String,
        characteristicUuid: String,
        serviceUuid: String?,
    ): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        if (!connection.isActive) return false
        val characteristic =
            findCharacteristic(connection.gatt, characteristicUuid, serviceUuid) ?: return false
        // The read forces link re-encryption on a fresh reconnect: a long gap
        // until the read response is the stack encrypting, not the queue
        Timber.d("Issuing read of %s to the stack", characteristicUuid)
        return connection.gatt.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    override fun initiateSubscribe(
        identifier: String,
        characteristicUuid: String,
        enable: Boolean,
        indication: Boolean,
        serviceUuid: String?,
    ): Boolean {
        val address = identifier.uppercase()
        val connection = connections[address] ?: return false
        if (!connection.isActive) return false
        val characteristic =
            findCharacteristic(connection.gatt, characteristicUuid, serviceUuid) ?: return false
        val descriptor =
            characteristic.getDescriptor(UUID.fromString(SonyBluetoothConstants.CCCD_UUID))
                ?: return false

        if (!connection.gatt.setCharacteristicNotification(characteristic, enable)) return false

        pendingSubscribeEnable["$address:${characteristicUuid.lowercase()}"] = enable
        val properties = characteristic.properties
        val canNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val canIndicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val descriptorValue = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            // Indication-only characteristics ignore the notification bit (Fujifilm).
            canIndicate && (indication || !canNotify) ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        return writeDescriptorCompat(connection.gatt, descriptor, descriptorValue)
    }

    @SuppressLint("MissingPermission")
    override fun initiateDiscoverServices(identifier: String): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        if (!connection.isActive) return false
        return connection.gatt.discoverServices()
    }

    // ---------------------------------------------------------------------------
    // GATT callback — feeds the event channel only, from binder threads
    // ---------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address.uppercase()

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                val connection = connections[address]
                if (connection?.gatt !== gatt) {
                    // A handle replaced meanwhile (direct ↔ autoConnect) must not start a session.
                    Timber.d("Ignoring connection of a replaced GATT handle for %s", address)
                    return
                }
                Timber.i("Connected to device with status %d", status)
                connection.isActive = true
                // Discovery + handshake are dozens of sequential ATT round trips,
                // each costing one connection interval (~50ms at the default).
                // Request the short interval for setup; relaxConnection() drops
                // it again after the handshake to save battery.
                if (!gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)) {
                    Timber.w("Could not request high connection priority for %s", address)
                }
                // Android 14+ grants 517 to the FIRST client that requests an MTU
                // and ignores every later request — ask ourselves right away so
                // another app's value can't win. Fire-and-forget: discovery is not
                // gated on the exchange (result arrives in onMtuChanged). On API
                // 37+ the connection settings negotiate the MTU automatically.
                //  if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.CINNAMON_BUN) {
                /*    Timber.e("Requesting MTU 23 for %s (API %d)", address, Build.VERSION.SDK_INT)
                    if (!gatt.requestMtu(153)) {
                        Timber.w("Could not request MTU for %s", address)
                    }*/
                //   }
                eventChannel.trySend(BleTransportEvent.Connected(address))
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val connection = connections[address]?.takeIf { it.gatt === gatt }
                val wasActive = connection?.isActive == true
                connection?.isActive = false
                if (status == 19 || status == 8 || status == 0) {
                    Timber.i("Device disconnected in callback with status: $status")
                } else {
                    Timber.e("An error happened: $status")
                }
                if (connection != null && connection.direct) {
                    // A direct attempt that timed out never connected: no session to end.
                    if (wasActive) eventChannel.trySend(BleTransportEvent.Disconnected(address, status))
                    fallBackToAutoConnect(address, connection)
                    return
                }
                eventChannel.trySend(BleTransportEvent.Disconnected(address, status))
                return
            }

            Timber.d("Ignoring connection callback with status=$status and state=$newState")
        }

        /**
         * Hidden in the SDK but dispatched by signature at runtime: reports the
         * actual negotiated connection parameters (interval unit = 1.25 ms).
         * Tells us whether the CONNECTION_PRIORITY_HIGH request stuck or the
         * camera overrode it with its own preferred parameters.
         */
        /* @Suppress("unused")
         fun onConnectionUpdated(
             gatt: BluetoothGatt,
             interval: Int,
             latency: Int,
             timeout: Int,
             status: Int,
         ) {
             Timber.i(
                 "Connection updated for %s: interval=%d (%.1f ms), latency=%d, timeout=%d, status=%d",
                 gatt.device.address, interval, interval * 1.25, latency, timeout, status,
             )
         }*/

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Timber.i("MTU changed for %s: mtu=%d status=%d", gatt.device.address, mtu, status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Timber.i("Services discovered for ${gatt.device.address} with status $status")
            eventChannel.trySend(
                BleTransportEvent.ServicesDiscovered(
                    gatt.device.address.uppercase(),
                    status == BluetoothGatt.GATT_SUCCESS,
                )
            )
        }

        // Android 12+. The characteristics from the last discovery are stale now.
        override fun onServiceChanged(gatt: BluetoothGatt) {
            val address = gatt.device.address.uppercase()
            if (connections[address]?.gatt !== gatt) return
            Timber.i("Services changed on %s", address)
            eventChannel.trySend(BleTransportEvent.ServicesChanged(address))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            val uuid = characteristic?.uuid?.toString() ?: return
            eventChannel.trySend(
                BleTransportEvent.CharacteristicWritten(
                    gatt.device.address.uppercase(),
                    uuid,
                    statusOf(status),
                )
            )
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                emitCharacteristicRead(
                    gatt,
                    characteristic,
                    characteristic.value ?: ByteArray(0),
                    status
                )
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            emitCharacteristicRead(gatt, characteristic, value, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Timber.d("Characteristic changed: ${characteristic.uuid}, value=${value.joinToString(",")}")
            eventChannel.trySend(
                BleTransportEvent.CharacteristicChanged(
                    gatt.device.address.uppercase(),
                    characteristic.uuid.toString(),
                    value,
                )
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val address = gatt.device.address.uppercase()
            val characteristicUuid = descriptor.characteristic.uuid.toString()
            Timber.d("Descriptor write completed for ${gatt.device.address} with status $status")
            val enabled =
                pendingSubscribeEnable.remove("$address:${characteristicUuid.lowercase()}")
                    ?: true
            eventChannel.trySend(
                BleTransportEvent.SubscriptionChanged(
                    address,
                    characteristicUuid,
                    enabled,
                    statusOf(status),
                )
            )
        }

        private fun emitCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            Timber.d("Read response for %s with status %d", characteristic.uuid, status)
            eventChannel.trySend(
                BleTransportEvent.CharacteristicRead(
                    gatt.device.address.uppercase(),
                    characteristic.uuid.toString(),
                    value,
                    statusOf(status),
                )
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers (write compat absorbed from the former BluetoothGattUtils)
    // ---------------------------------------------------------------------------

    private fun statusOf(status: Int): BleOperationStatus = when (status) {
        BluetoothGatt.GATT_SUCCESS -> BleOperationStatus.Success

        SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_AUTHENTICATION,
        SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_ENCRYPTION,
            -> BleOperationStatus.AuthError

        else -> BleOperationStatus.Failure
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        uuid: String,
        serviceUuid: String? = null,
    ): BluetoothGattCharacteristic? {
        val target = UUID.fromString(uuid)
        if (serviceUuid != null) {
            return gatt.getService(UUID.fromString(serviceUuid))?.getCharacteristic(target)
        }
        return gatt.services?.flatMap { it.characteristics }?.find { it.uuid == target }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(characteristic, value, writeType)
            // The link can drop after the active check but before Android accepts the write.
            if (result == ERROR_DEVICE_NOT_CONNECTED) {
                Timber.d("Characteristic write not initiated because device disconnected")
                return false
            }
            // 201 == device busy, spams sentry, but I do not know the cause yet
            if (result != 0 && result != 201) {
                Timber.e("Writing characteristic failed. Result: $result")
                false
            } else {
                Timber.d("Characteristic written successfully (API 33+)")
                true
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            val result = gatt.writeCharacteristic(characteristic)
            if (!result) {
                Timber.e("Writing characteristic failed (legacy API)")
            }
            result
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(descriptor, value)
            if (result == ERROR_DEVICE_NOT_CONNECTED) {
                Timber.d("Descriptor write not initiated because device disconnected")
                return false
            }
            if (result != 0 && result != 201) {
                Timber.e("Writing descriptor failed. Result: $result")
                false
            } else {
                Timber.d("Descriptor written successfully (API 33+)")
                true
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            val result = gatt.writeDescriptor(descriptor)
            if (!result) {
                Timber.e("Writing descriptor failed (legacy API)")
            }
            result
        }
    }
}
