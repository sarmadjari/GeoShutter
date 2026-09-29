/*
 * Author: Sarmad Jari
 *
 * Expected packets were generated independently of this code with Python's
 * struct.pack('<iii4sHBBBBB', ...), the layout of furble's packed geotag struct
 * (https://github.com/gkoh/furble, lib/furble/Fujifilm.h, MIT License). The time
 * packet example is the worked example of the maintainer's analysis of Fujifilm's
 * app (struct.pack('<HBBBBBiB', ...)); see docs/fujifilm-protocol.md.
 */
package com.sasch.cameragps.sharednew.bluetooth.fujifilm

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FujifilmPacketBuilderTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun geotagPacketMatchesFurblesLittleEndianLayout() {
        val packet = FujifilmPacketBuilder.buildGeotagPacket(
            latitude = 25.2048,
            longitude = 55.2708,
            altitudeMeters = 5.0,
            utc = LocalDateTime(2026, 9, 28, 12, 34, 56),
        )

        assertEquals(FujifilmPacketBuilder.GEOTAG_PACKET_SIZE, packet.size)
        assertContentEquals(
            bytes(
                0x80, 0xF2, 0x05, 0x0F, // latitude × 10⁷
                0xA0, 0xA7, 0xF1, 0x20, // longitude × 10⁷
                0x05, 0x00, 0x00, 0x00, // altitude
                0x00, 0x00, 0x00, 0x00, // padding
                0xEA, 0x07, 0x09, 0x1C, 0x0C, 0x22, 0x38, // 2026-09-28 12:34:56
            ),
            packet,
        )
    }

    @Test
    fun negativeValuesAreTruncatedTowardZeroAndLeapDaysEncode() {
        val packet = FujifilmPacketBuilder.buildGeotagPacket(
            latitude = -33.4489,
            longitude = -70.6693,
            altitudeMeters = -12.7,
            utc = LocalDateTime(2024, 2, 29, 23, 59, 59),
        )

        assertContentEquals(
            bytes(
                0x58, 0x1A, 0x10, 0xEC,
                0x78, 0xB8, 0xE0, 0xD5,
                0xF4, 0xFF, 0xFF, 0xFF,
                0x00, 0x00, 0x00, 0x00,
                0xE8, 0x07, 0x02, 0x1D, 0x17, 0x3B, 0x3B,
            ),
            packet,
        )
    }

    @Test
    fun anUnknownAltitudeIsSentAsZero() {
        val packet = FujifilmPacketBuilder.buildGeotagPacket(
            latitude = 0.0000001,
            longitude = -0.0000001,
            altitudeMeters = null,
            utc = LocalDateTime(2000, 1, 1, 0, 0, 0),
        )

        assertContentEquals(
            bytes(
                0x01, 0x00, 0x00, 0x00,
                0xFF, 0xFF, 0xFF, 0xFF,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0xD0, 0x07, 0x01, 0x01, 0x00, 0x00, 0x00,
            ),
            packet,
        )
    }

    @Test
    fun timeSyncPacketCarriesUtcTheStandardOffsetAndTheDaylightSavingFlag() {
        // 2026-09-29 12:34:56 UTC, UTC+1:00 with daylight saving time (e.g. Oslo in summer).
        val packet = FujifilmPacketBuilder.buildTimeSyncPacket(
            standardOffsetMinutes = 60,
            dstOffsetMinutes = 60,
            utc = LocalDateTime(2026, 9, 29, 12, 34, 56),
        )

        assertContentEquals(
            bytes(0xEA, 0x07, 0x09, 0x1D, 0x0C, 0x22, 0x38, 0x64, 0x00, 0x00, 0x00, 0x01),
            packet,
        )
    }

    @Test
    fun timeZoneOffsetsAreSentInHundredthsOfAnHour() {
        fun offset(standardOffsetMinutes: Int, dstOffsetMinutes: Int = 0): Int {
            val packet = FujifilmPacketBuilder.buildTimeSyncPacket(
                standardOffsetMinutes,
                dstOffsetMinutes,
                LocalDateTime(2026, 1, 15, 8, 0, 0),
            )
            return (packet[7].toInt() and 0xFF) or ((packet[8].toInt() and 0xFF) shl 8) or
                    ((packet[9].toInt() and 0xFF) shl 16) or ((packet[10].toInt() and 0xFF) shl 24)
        }

        assertEquals(0, offset(0))
        assertEquals(100, offset(60))
        assertEquals(550, offset(330)) // India
        assertEquals(575, offset(345)) // Nepal
        assertEquals(1275, offset(765)) // Chatham Islands
        assertEquals(-350, offset(-210)) // Newfoundland
        assertEquals(-950, offset(-570)) // Marquesas
        assertEquals(-500, offset(-300))
        // Daylight saving time stays out of the offset: it has its own flag.
        assertEquals(100, offset(60, dstOffsetMinutes = 60))
    }

    @Test
    fun theDaylightSavingFlagFollowsThePhone() {
        val winter = FujifilmPacketBuilder.buildTimeSyncPacket(60, 0, LocalDateTime(2026, 1, 15, 8, 0))
        val summer = FujifilmPacketBuilder.buildTimeSyncPacket(60, 60, LocalDateTime(2026, 7, 15, 8, 0))
        assertEquals(0, winter[11].toInt())
        assertEquals(1, summer[11].toInt())
    }

    @Test
    fun negativeDaylightSavingIsSentAsTheCurrentOffset() {
        // Europe/Dublin in winter, as the tz database models it: standard +1:00, "DST" -1:00.
        val packet = FujifilmPacketBuilder.buildTimeSyncPacket(60, -60, LocalDateTime(2026, 1, 15, 8, 0))

        assertContentEquals(bytes(0x00, 0x00, 0x00, 0x00), packet.copyOfRange(7, 11))
        assertEquals(0, packet[11].toInt())
    }

    @Test
    fun thePowerKeyStateTellsOnFromOff() {
        // Read from an X100VI: switched on, then switched off in standby.
        assertEquals(true, FujifilmPacketBuilder.isPoweredOn(bytes(0x01, 0x02)))
        assertEquals(false, FujifilmPacketBuilder.isPoweredOn(bytes(0x00, 0x01)))
        // The other values in Fujifilm's app.
        assertEquals(false, FujifilmPacketBuilder.isPoweredOn(bytes(0x00, 0x02)))
        assertEquals(true, FujifilmPacketBuilder.isPoweredOn(bytes(0x01, 0x01)))
        assertNull(FujifilmPacketBuilder.isPoweredOn(bytes(0x00, 0x00)))
        assertNull(FujifilmPacketBuilder.isPoweredOn(bytes(0x01)))
    }

    @Test
    fun statusIsAcknowledgedWithByteThreeSetTo0x20() {
        // The value furble's comment gives: 0x07960000 -> 0x07960020.
        assertContentEquals(
            bytes(0x07, 0x96, 0x00, 0x20),
            FujifilmPacketBuilder.statusAck(bytes(0x07, 0x96, 0x00, 0x00)),
        )
        assertNull(FujifilmPacketBuilder.statusAck(bytes(0x07, 0x96, 0x00)))
        assertNull(FujifilmPacketBuilder.statusAck(bytes(0x07, 0x96, 0x00, 0x00, 0x00)))
    }

    @Test
    fun syncIntervalIsALittleEndianUint16() {
        assertContentEquals(bytes(0x0A, 0x00), FujifilmPacketBuilder.syncInterval())
        assertContentEquals(bytes(0x2C, 0x01), FujifilmPacketBuilder.syncInterval(300))
    }

    @Test
    fun notificationsAreMatchedOnTheirFirstTwoBytes() {
        assertTrue(FujifilmPacketBuilder.isGeotagRequest(bytes(0x01, 0x00)))
        assertTrue(FujifilmPacketBuilder.isGeotagRequest(bytes(0x01, 0x00, 0x7F)))
        assertFalse(FujifilmPacketBuilder.isGeotagRequest(bytes(0x01)))
        assertFalse(FujifilmPacketBuilder.isGeotagRequest(bytes(0x02, 0x00)))
        assertTrue(FujifilmPacketBuilder.isConfigured(bytes(0x02, 0x00)))
        assertFalse(FujifilmPacketBuilder.isConfigured(bytes(0x01, 0x00)))
    }
}
