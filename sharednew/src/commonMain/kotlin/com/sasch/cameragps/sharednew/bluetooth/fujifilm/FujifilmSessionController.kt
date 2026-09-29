/*
 * Fujifilm secure-protocol session setup and notification handling.
 *
 * Author: Sarmad Jari
 *
 * The handshake order is ported from furble's FujifilmSecure::_connect and the
 * notification handling from Fujifilm::notify (https://github.com/gkoh/furble,
 * commit 0cac22aacc3d9ef250be40849118af78a5f39f27, lib/furble/FujifilmSecure.cpp
 * and Fujifilm.cpp), Copyright (c) 2020 Guo-Rong Koh, MIT License. Unverified on
 * the X100VI; see docs/fujifilm-protocol.md.
 */
package com.sasch.cameragps.sharednew.bluetooth.fujifilm

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.PlatformTimeZoneInfo
import com.sasch.cameragps.sharednew.bluetooth.session.PairingRetryPolicy
import com.sasch.cameragps.sharednew.bluetooth.session.QueuedBleGattPort
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationResult
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants as Fuji

/** What service discovery revealed about a camera. */
internal enum class CameraDetection {
    /** Sony, or a camera without any known Fujifilm characteristic (unchanged behavior). */
    Sony,

    /** Fujifilm with the secure protocol (firmware from about July 2025). */
    FujifilmSecure,

    /** Fujifilm with the legacy token protocol, which is not supported. */
    FujifilmLegacy,
}

internal sealed interface FujifilmHandshakeResult {
    data object Success : FujifilmHandshakeResult

    /** A required step failed; [step] names it for the log. */
    data class Failed(val step: String) : FujifilmHandshakeResult

    /** The camera kept answering with authentication errors: pairing was refused. */
    data object PairingRejected : FujifilmHandshakeResult
}

internal enum class FujifilmNotification {
    GeotagRequested,

    /** NOT1 changed: Fujifilm's app sets the camera clock again on every such event. */
    DateSyncRequested,

    /** NOT7 changed: the camera's SMARTPHONE LOCATION SYNC. setting. */
    LocationSyncSettingChanged,
    Other,
}

/**
 * Runs the Fujifilm secure handshake as one sequence of queued operations and
 * interprets the camera's notifications. Location delivery itself lives in the
 * location transmission manager, which answers geotag requests.
 */
internal class FujifilmSessionController(
    private val port: QueuedBleGattPort,
    private val pairingPolicy: PairingRetryPolicy,
    private val clientName: String = Fuji.CLIENT_NAME,
) {
    private val log = logging()

    /**
     * Sony is checked first so a Sony camera always keeps its existing path; a
     * camera matching neither also takes the Sony path, as before.
     */
    fun detect(identifier: String): CameraDetection = when {
        port.hasCharacteristic(identifier, SonyBluetoothConstants.CHARACTERISTIC_UUID) ->
            CameraDetection.Sony

        port.hasCharacteristic(identifier, Fuji.STATUS_CHARACTERISTIC_UUID) ->
            CameraDetection.FujifilmSecure

        port.hasCharacteristic(identifier, Fuji.LEGACY_PAIR_CHARACTERISTIC_UUID) ->
            CameraDetection.FujifilmLegacy

        else -> CameraDetection.Sony
    }

    /**
     * furble's order: read status → write it back with byte 3 = 0x20 → write the
     * client name → required subscriptions → optional subscriptions (the sync
     * interval one is required) → write the sync interval.
     */
    suspend fun runHandshake(identifier: String): FujifilmHandshakeResult {
        val id = identifier.uppercase()

        val statusResult = execute(
            id,
            BleOperation.Read(Fuji.STATUS_CHARACTERISTIC_UUID, Fuji.PAIR_SERVICE_UUID),
        )
        if (statusResult.isPairingRejection()) return FujifilmHandshakeResult.PairingRejected
        val status = (statusResult as? BleOperationResult.Success)?.value
        log.d { "Fujifilm[$id]: status ${status?.toHex() ?: statusResult}" }
        val ack = status?.let(FujifilmPacketBuilder::statusAck)
            ?: return FujifilmHandshakeResult.Failed("status read")
        log.d { "Fujifilm[$id]: acknowledging status with ${ack.toHex()}" }

        write(id, Fuji.STATUS_CHARACTERISTIC_UUID, Fuji.PAIR_SERVICE_UUID, ack)
            ?.let { return it }
        write(id, Fuji.IDENTIFIER_CHARACTERISTIC_UUID, Fuji.PAIR_SERVICE_UUID, clientName.encodeToByteArray())
            ?.let { return it }

        for (subscription in Fuji.REQUIRED_SUBSCRIPTIONS) {
            subscribe(id, subscription)?.let { return it }
        }
        for (subscription in Fuji.OPTIONAL_SUBSCRIPTIONS) {
            val failure = subscribe(id, subscription) ?: continue
            if (subscription.characteristicUuid == Fuji.GEOTAG_SYNC_INTERVAL_UUID) return failure
            log.w { "Fujifilm[$id]: optional subscription ${subscription.characteristicUuid} failed, continuing" }
        }

        val interval = FujifilmPacketBuilder.syncInterval()
        log.d { "Fujifilm[$id]: setting the geotag sync interval to ${interval.toHex()}" }
        write(id, Fuji.GEOTAG_SYNC_INTERVAL_UUID, Fuji.NOTIFICATION_SERVICE_UUID, interval)
            ?.let { return it }

        if (!port.hasCharacteristic(id, Fuji.SHUTTER_CHARACTERISTIC_UUID)) {
            // furble requires it; geotagging does not.
            log.w { "Fujifilm[$id]: no shutter characteristic" }
        }
        log.i { "Fujifilm[$id]: handshake complete" }
        return FujifilmHandshakeResult.Success
    }

    /**
     * The camera's own name from NOT4, e.g. "X100VI-1A2B" (the "FUJIFILM-" prefix is
     * dropped), or null when it can't be read.
     */
    suspend fun readCameraName(identifier: String): String? {
        val id = identifier.uppercase()
        val result = port.execute(
            id,
            BleOperation.Read(Fuji.NOTIFICATION_4_UUID, Fuji.NOTIFICATION_SERVICE_UUID),
        )
        val value = (result as? BleOperationResult.Success)?.value ?: return null
        return value.decodeToString()
            .substringBefore('\u0000')
            .trim()
            .removePrefix(Fuji.CAMERA_NAME_PREFIX)
            .takeIf { name -> name.isNotEmpty() && name.all { it.code in 32..126 } }
    }

    /**
     * Sets the camera clock and time zone to the phone's
     * ([Fuji.UTC_TIME_ZONE_UUID]). False when the camera has no time service or the
     * write failed.
     */
    suspend fun syncTime(identifier: String): Boolean {
        val id = identifier.uppercase()
        if (!port.hasCharacteristic(id, Fuji.UTC_TIME_ZONE_UUID)) {
            log.w { "Fujifilm[$id]: no time characteristic, the camera clock is left alone" }
            return false
        }
        val zone = PlatformTimeZoneInfo()
        val packet = FujifilmPacketBuilder.buildTimeSyncPacket(
            standardOffsetMinutes = zone.standardOffsetMinutes,
            dstOffsetMinutes = zone.dstOffsetMinutes,
        )
        log.i { "Fujifilm[$id]: setting the date, time and time zone to ${packet.toHex()}" }
        val result = execute(
            id,
            BleOperation.Write(Fuji.UTC_TIME_ZONE_UUID, packet, Fuji.TIME_SERVICE_UUID),
        )
        if (result !is BleOperationResult.Success) {
            log.w { "Fujifilm[$id]: setting the time failed: $result" }
            return false
        }
        return true
    }

    /** The power switch value (see [FujifilmPacketBuilder.isAwake]), or null if unavailable. */
    suspend fun readPowerSwitch(identifier: String): ByteArray? {
        val id = identifier.uppercase()
        if (!port.hasCharacteristic(id, Fuji.POWER_SWITCH_UUID)) return null
        val result = port.execute(id, BleOperation.Read(Fuji.POWER_SWITCH_UUID))
        return (result as? BleOperationResult.Success)?.value
    }

    /** Interpret a notification or indication from a Fujifilm camera. */
    fun onCharacteristicChanged(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): FujifilmNotification {
        log.d { "Fujifilm[$identifier]: $characteristicUuid changed to ${value.toHex()}" }
        return when {
            characteristicUuid.equals(Fuji.GEOTAG_REQUEST_UUID, ignoreCase = true) &&
                    FujifilmPacketBuilder.isGeotagRequest(value) -> FujifilmNotification.GeotagRequested

            characteristicUuid.equals(Fuji.NOTIFICATION_1_UUID, ignoreCase = true) ->
                FujifilmNotification.DateSyncRequested

            characteristicUuid.equals(Fuji.LOCATION_SYNC_SETTING_UUID, ignoreCase = true) ->
                FujifilmNotification.LocationSyncSettingChanged

            else -> FujifilmNotification.Other
        }
    }

    private suspend fun write(
        id: String,
        characteristicUuid: String,
        serviceUuid: String,
        value: ByteArray,
    ): FujifilmHandshakeResult? {
        val result = execute(id, BleOperation.Write(characteristicUuid, value, serviceUuid))
        return when {
            result is BleOperationResult.Success -> null
            result.isPairingRejection() -> FujifilmHandshakeResult.PairingRejected
            else -> {
                log.w { "Fujifilm[$id]: write to $characteristicUuid failed: $result" }
                FujifilmHandshakeResult.Failed("write $characteristicUuid")
            }
        }
    }

    private suspend fun subscribe(
        id: String,
        subscription: Fuji.Subscription,
    ): FujifilmHandshakeResult? {
        val result = execute(
            id,
            BleOperation.Subscribe(
                subscription.characteristicUuid,
                enable = true,
                indication = subscription.indication,
                serviceUuid = subscription.serviceUuid,
            ),
        )
        return when {
            result is BleOperationResult.Success -> null
            result.isPairingRejection() -> FujifilmHandshakeResult.PairingRejected
            else -> {
                log.w { "Fujifilm[$id]: subscription to ${subscription.characteristicUuid} failed: $result" }
                FujifilmHandshakeResult.Failed("subscribe ${subscription.characteristicUuid}")
            }
        }
    }

    /**
     * One queued operation, retried on authentication errors like the Sony path:
     * on reconnect such an error usually only means encryption is still being
     * re-established.
     */
    private suspend fun execute(id: String, operation: BleOperation): BleOperationResult {
        var attempt = 0
        while (true) {
            val result = port.execute(id, operation)
            if (!result.isAuthError()) return result
            if (!port.isConnected(id)) return BleOperationResult.Cancelled
            attempt++
            // Retries used up: the caller reports the pairing as rejected.
            if (attempt > pairingPolicy.maxRetries) return result
            val delayMs =
                if (attempt == 1) pairingPolicy.firstRetryDelayMs else pairingPolicy.retryDelayMs
            log.w { "Fujifilm[$id]: authentication error, retry $attempt/${pairingPolicy.maxRetries}" }
            if (delayMs > 0) delay(delayMs.milliseconds)
            if (!port.isConnected(id)) return BleOperationResult.Cancelled
        }
    }

    private fun BleOperationResult.isAuthError() =
        this is BleOperationResult.Failure && status == BleOperationStatus.AuthError

    /** An authentication error that survived every retry. */
    private fun BleOperationResult.isPairingRejection() = isAuthError()

    private fun ByteArray.toHex(): String =
        joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
