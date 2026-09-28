/*
 * Fujifilm geotag and handshake payloads.
 *
 * Author: Sarmad Jari
 *
 * Byte layouts ported from furble (https://github.com/gkoh/furble, commit
 * 0cac22aacc3d9ef250be40849118af78a5f39f27), Copyright (c) 2020 Guo-Rong Koh,
 * MIT License: the packed geotag struct in lib/furble/Fujifilm.h:72-92, filled
 * in Fujifilm.cpp:93-130, and the status ack and sync interval in
 * FujifilmSecure.cpp:118-128 and 186-192. furble runs on a little-endian ESP32.
 */
package com.sasch.cameragps.sharednew.bluetooth.fujifilm

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

object FujifilmPacketBuilder {

    /** `int32 lat, int32 lon, int32 alt, 4 pad, uint16 year, month, day, hour, minute, second`. */
    const val GEOTAG_PACKET_SIZE = 23

    /**
     * The geotag packet written in answer to a geotag request.
     *
     * Coordinates are degrees × 10⁷ and the altitude whole metres, all truncated
     * toward zero like furble's C casts. The time is UTC: furble takes it from a
     * GPS receiver's NMEA sentences.
     */
    @Suppress("DEPRECATION")
    fun buildGeotagPacket(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?,
        utc: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
    ): ByteArray {
        val packet = ByteArray(GEOTAG_PACKET_SIZE)
        var offset = 0
        fun int32(value: Int) {
            packet[offset++] = value.toByte()
            packet[offset++] = (value shr 8).toByte()
            packet[offset++] = (value shr 16).toByte()
            packet[offset++] = (value shr 24).toByte()
        }
        int32((latitude * 1.0E7).toInt())
        int32((longitude * 1.0E7).toInt())
        int32(altitudeMeters?.toInt() ?: 0)
        offset += 4 // padding, left 0x00
        packet[offset++] = utc.year.toByte()
        packet[offset++] = (utc.year shr 8).toByte()
        packet[offset++] = utc.monthNumber.toByte()
        packet[offset++] = utc.dayOfMonth.toByte()
        packet[offset++] = utc.hour.toByte()
        packet[offset++] = utc.minute.toByte()
        packet[offset] = utc.second.toByte()
        return packet
    }

    /** The status value written back: the first three bytes unchanged, byte 3 set to 0x20. */
    fun statusAck(status: ByteArray): ByteArray? {
        if (status.size != FujifilmBluetoothConstants.STATUS_LENGTH) return null
        return byteArrayOf(status[0], status[1], status[2], FujifilmBluetoothConstants.STATUS_ACK_BYTE)
    }

    /** The sync interval in seconds as a little-endian uint16. */
    fun syncInterval(seconds: Int = FujifilmBluetoothConstants.GEOTAG_SYNC_INTERVAL_SECONDS): ByteArray =
        byteArrayOf(seconds.toByte(), (seconds shr 8).toByte())

    /** `true` for the camera's geotag request (`01 00`, longer payloads included). */
    fun isGeotagRequest(value: ByteArray): Boolean = value.startsWith(FujifilmBluetoothConstants.GEOTAG_REQUEST)

    /** `true` for notification 1's "configured" value (`02 00`). */
    fun isConfigured(value: ByteArray): Boolean = value.startsWith(FujifilmBluetoothConstants.CONFIGURED)

    // furble checks `length >= 2` and the first two bytes (Fujifilm.cpp:25, 29).
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
