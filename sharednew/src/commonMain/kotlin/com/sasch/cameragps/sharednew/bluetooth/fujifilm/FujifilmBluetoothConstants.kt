/*
 * Fujifilm Bluetooth LE protocol constants.
 *
 * Author: Sarmad Jari
 *
 * Most values are ported from furble (https://github.com/gkoh/furble, commit
 * 0cac22aacc3d9ef250be40849118af78a5f39f27, lib/furble/Fujifilm*.{h,cpp}),
 * Copyright (c) 2020 Guo-Rong Koh, MIT License. Fujifilm publishes no protocol
 * documentation; furble reverse-engineered it from HCI snoop logs of the official
 * app. The time service comes from other sources. What has been verified on an
 * X100VI is listed in docs/fujifilm-protocol.md.
 */
package com.sasch.cameragps.sharednew.bluetooth.fujifilm

object FujifilmBluetoothConstants {

    /** Bluetooth SIG company ID in the advertised manufacturer data (Fujifilm.h:35). */
    const val COMPANY_ID = 0x04D8

    // ---- Secure protocol (firmware from about July 2025), FujifilmSecure.{h,cpp} ----

    /** Pairing service holding the status and identifier characteristics (FujifilmSecure.h:71). */
    const val PAIR_SERVICE_UUID = "123d8f06-62a1-4935-9322-833c531ee225"

    /**
     * Status: read 4 bytes, write them back with byte 3 set to [STATUS_ACK_BYTE]
     * (FujifilmSecure.h:74, FujifilmSecure.cpp:118-128). Its presence identifies a
     * secure-protocol camera.
     */
    const val STATUS_CHARACTERISTIC_UUID = "f557d96b-8284-4667-8793-b971c1deca2a"
    const val STATUS_ACK_BYTE: Byte = 0x20
    const val STATUS_LENGTH = 4

    /** Identifier: UTF-8 client name, written with response (FujifilmSecure.h:77, .cpp:135-141). */
    const val IDENTIFIER_CHARACTERISTIC_UUID = "85b9163e-62d1-49ff-a6f5-054b4630d4a1"

    /** Configuration service with the indication/notification characteristics (Fujifilm.h:45). */
    const val CONFIG_SERVICE_UUID = "4c0020fe-f3b6-40de-acc9-77d129067b14"
    const val INDICATION_1_UUID = "a68e3f66-0fcc-4395-8d4c-aa980b5877fa"
    const val INDICATION_2_UUID = "bd17ba04-b76b-4892-a545-b73ba1f74dae"

    /**
     * Notification 1: `02 00` means the camera is configured (Fujifilm.cpp:24-27).
     * Fujifilm's app names it the date sync state and sets the clock again whenever it
     * changes, whatever the value.
     */
    const val NOTIFICATION_1_UUID = "f9150137-5d40-4801-a8dc-f7fc5b01da50"

    /** Geotag request: the camera notifies [GEOTAG_REQUEST] when it wants a location (Fujifilm.h:59, .cpp:28-31). */
    const val GEOTAG_REQUEST_UUID = "ad06c7b7-f41a-46f4-a29a-712055319122"
    val GEOTAG_REQUEST = byteArrayOf(0x01, 0x00)
    val CONFIGURED = byteArrayOf(0x02, 0x00)

    /** Notification 6, in the configuration service (FujifilmSecure.h:87). */
    const val NOTIFICATION_6_UUID = "e6692c5c-b7cd-44f4-95fc-eda07ce32560"

    /** Second notification service (FujifilmSecure.h:90-97). */
    const val NOTIFICATION_SERVICE_UUID = "4e941240-d01d-46b9-a5ea-67636806830b"
    const val NOTIFICATION_4_UUID = "bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4"
    const val NOTIFICATION_5_UUID = "75823784-fbb7-4b71-abae-cd9a34072e3c"
    const val NOTIFICATION_7_UUID = "aab609c4-94dd-4d89-bc60-665d5090b828"

    /**
     * NOT7 is the camera's SMARTPHONE LOCATION SYNC. setting: little-endian uint16,
     * 0 off, 1 on, readable, writable and notified on change.
     */
    const val LOCATION_SYNC_SETTING_UUID = NOTIFICATION_7_UUID
    const val NOTIFICATION_8_UUID = "2a125640-706d-4dd1-b420-c0f4ab93c361"
    const val NOTIFICATION_9_UUID = "82a9f452-c5ce-4ef5-8203-3fc9a47f8171"
    const val NOTIFICATION_10_UUID = "deef7187-3f43-4364-9e22-11a8c8a15951"

    /**
     * Geotag sync interval: subscribe, then write the interval in seconds as a
     * little-endian uint16 (FujifilmSecure.h:97, .cpp:186-192; value Fujifilm.h:62).
     */
    const val GEOTAG_SYNC_INTERVAL_UUID = "c95d91ae-b247-4d6d-8661-7dd5d6a0f85b"
    const val GEOTAG_SYNC_INTERVAL_SECONDS = 10

    /** Geotag service and characteristic: 23-byte packet, written with response (Fujifilm.h:98-99, .cpp:93-130). */
    const val GEOTAG_SERVICE_UUID = "3b46ec2b-48ba-41fd-b1b8-ed860b60d22b"
    const val GEOTAG_CHARACTERISTIC_UUID = "0f36ec14-29e5-411a-a1b6-64ee8383f090"

    /** Remote shutter (not used yet; Fujifilm.h:56 and 101-104, FujifilmSecure.h:100). */
    const val SHUTTER_SERVICE_UUID = "6514eb81-4e8f-458d-aa2a-e691336cdfac"
    const val SHUTTER_CHARACTERISTIC_UUID = "7fcf49c6-4ff0-4777-a03d-1a79166af7a8"

    /**
     * The camera's power switch, uint16 (Fujifilm's app: CAMERA_POWER_KEY_STATE, in the
     * camera startup information service, `804daa8e-ffeb-4ab3-8e75-6edd7303208d` on the
     * X100VI): `0x0201` on, `0x0200` off, `0x0101` on and `0x0100` off in standby. Not in
     * furble; see docs/fujifilm-protocol.md.
     */
    const val POWER_SWITCH_UUID = "f90f7d3a-3b64-45c6-ab21-933900184837"

    // ---- Date, time and time zone (not in furble; see docs/fujifilm-protocol.md) ----

    /**
     * Time service. furble's source once named it, with [UTC_TIME_ZONE_UUID], as the
     * "time sync message" seen in a capture of Fujifilm's app (FujifilmSecure.cpp at
     * commit 4dc076a); the X100VI firmware's GATT table (tiredboffin/fffw) lists both.
     */
    const val TIME_SERVICE_UUID = "e872b11f-d526-4ae1-9bb4-89a99d48fa59"

    /**
     * The camera clock and time zone: 12 bytes, see
     * [FujifilmPacketBuilder.buildTimeSyncPacket]. Written with response.
     */
    const val UTC_TIME_ZONE_UUID = "c52edbce-1fe2-4ecc-9483-907e6592be9e"
    const val TIME_SYNC_PACKET_SIZE = 12

    /** Advertised by a secure camera in pairing mode (FujifilmSecure.cpp:13, 19-23). */
    const val SECURE_ADVERTISED_SERVICE_UUID = "a9d2b304-e8d6-4902-8336-352b772d7597"

    // ---- Legacy "basic" protocol (older firmware), detection only ----

    /** Legacy pairing characteristic, written with a token from the advertisement (Fujifilm.h:40, FujifilmBasic.cpp). */
    const val LEGACY_PAIR_CHARACTERISTIC_UUID = "aba356eb-9633-4e60-b73f-f52516dbd671"

    /** Name the app identifies itself with; shown by the camera for the paired phone. */
    const val CLIENT_NAME = "GeoShutter"

    /**
     * Prefix of the camera's own name in NOT4 ([NOTIFICATION_4_UUID]), readable on the
     * X100VI as "FUJIFILM-X100VI-" plus four serial characters. The standard GAP device
     * name only holds the model ("X100VI").
     */
    const val CAMERA_NAME_PREFIX = "FUJIFILM-"

    /** One subscription step of the secure handshake, in furble's order. */
    data class Subscription(
        val serviceUuid: String,
        val characteristicUuid: String,
        val indication: Boolean,
    )

    /** Must all succeed (FujifilmSecure.cpp:144-161). */
    val REQUIRED_SUBSCRIPTIONS = listOf(
        Subscription(CONFIG_SERVICE_UUID, INDICATION_1_UUID, indication = true),
        Subscription(CONFIG_SERVICE_UUID, INDICATION_2_UUID, indication = true),
        Subscription(CONFIG_SERVICE_UUID, NOTIFICATION_1_UUID, indication = false),
        Subscription(CONFIG_SERVICE_UUID, GEOTAG_REQUEST_UUID, indication = false),
        Subscription(NOTIFICATION_SERVICE_UUID, NOTIFICATION_4_UUID, indication = false),
        Subscription(NOTIFICATION_SERVICE_UUID, NOTIFICATION_5_UUID, indication = false),
    )

    /**
     * Tried in order; failures are tolerated except for the sync interval
     * (FujifilmSecure.cpp:163-183).
     */
    val OPTIONAL_SUBSCRIPTIONS = listOf(
        Subscription(CONFIG_SERVICE_UUID, NOTIFICATION_6_UUID, indication = false),
        Subscription(NOTIFICATION_SERVICE_UUID, NOTIFICATION_7_UUID, indication = false),
        Subscription(NOTIFICATION_SERVICE_UUID, NOTIFICATION_8_UUID, indication = false),
        Subscription(NOTIFICATION_SERVICE_UUID, NOTIFICATION_9_UUID, indication = false),
        Subscription(NOTIFICATION_SERVICE_UUID, NOTIFICATION_10_UUID, indication = false),
        Subscription(NOTIFICATION_SERVICE_UUID, GEOTAG_SYNC_INTERVAL_UUID, indication = false),
    )
}
