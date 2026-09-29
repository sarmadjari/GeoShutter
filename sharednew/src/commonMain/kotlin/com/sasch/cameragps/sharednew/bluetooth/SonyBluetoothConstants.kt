package com.sasch.cameragps.sharednew.bluetooth

object SonyBluetoothConstants {
    /** Bluetooth SIG company ID in the advertised manufacturer data. */
    const val COMPANY_ID = 0x012D

    // Service UUID of the sony cameras
    val SERVICE_UUID = "8000dd00-dd00-ffff-ffff-ffffffffffff"

    val CONTROL_SERVICE_UUID = "8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF"

    // Characteristic for the location services
    val CHARACTERISTIC_UUID = "0000dd11-0000-1000-8000-00805f9b34fb"
    val CHARACTERISTIC_READ_UUID = "0000dd21-0000-1000-8000-00805f9b34fb"

    // needed for some cameras to enable the functionality
    val CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND = "0000dd30-0000-1000-8000-00805f9b34fb"
    val CHARACTERISTIC_ENABLE_LOCK_GPS_COMMAND = "0000dd31-0000-1000-8000-00805f9b34fb"

    val CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA = "0000dd01-0000-1000-8000-00805f9b34fb"

    val TIME_SYNC_CHARACTERISTIC_UUID = "0000cc13-0000-1000-8000-00805f9b34fb"

    /**
     * CC02: camera control. Takes Creators' App's "auto power off avoidance" command
     * ([KEEP_AWAKE_COMMAND]); verified on an α1 II: it restarts the camera's power save
     * timer, so it must be repeated sooner than the shortest Power Save Start Time (10 s).
     */
    val CAMERA_CONTROL_UUID = "0000cc02-0000-1000-8000-00805f9b34fb"

    val AUTO_TIME_CORRECTION_UUID = "0000dd32-0000-1000-8000-00805f9b34fb"
    val AUTO_AREA_ADJUSTMENT_UUID = "0000dd33-0000-1000-8000-00805f9b34fb"

    val REMOTE_SERVICE_UUID = "8000ff00-ff00-ffff-ffff-ffffffffffff"

    val REMOTE_CHARACTERISTIC_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"

    val REMOTE_STATUS_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
    val CAMERA_STATUS_UUID = "0000cc09-0000-1000-8000-00805f9b34fb"

    val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val ACTION_REQUEST_SHUTDOWN = "com.saschl.cameragps.ACTION_REQUEST_SHUTDOWN"
    const val ACTION_TRIGGER_REMOTE_SHUTTER = "com.saschl.cameragps.ACTION_TRIGGER_REMOTE_SHUTTER"
    const val ACTION_SEND_REMOTE_COMMAND = "com.saschl.cameragps.ACTION_SEND_REMOTE_COMMAND"
    const val ACTION_TRIGGER_SHUTTER_SEQUENCE =
        "com.saschl.cameragps.ACTION_TRIGGER_SHUTTER_SEQUENCE"
    const val ACTION_SET_REMOTE_CONTROL_MONITORING =
        "com.saschl.cameragps.ACTION_SET_REMOTE_CONTROL_MONITORING"

    // ATT error codes indicating a pairing/encryption problem (same values on Android GATT)
    const val ATT_ERROR_INSUFFICIENT_AUTHENTICATION = 5
    const val ATT_ERROR_INSUFFICIENT_ENCRYPTION = 15

    /**
     * Sony's own ATT error: the operation isn't available in the camera's current state.
     * A camera switched off with "Cnct. while Power OFF" on refuses the GPS setup and
     * location writes with it (seen on an α1 II and, per third parties, an α7R V).
     */
    const val ATT_ERROR_NOT_AVAILABLE = 0x9D

    /** Auto power off avoidance, written to [CAMERA_CONTROL_UUID] (Creators' App). */
    val KEEP_AWAKE_COMMAND = byteArrayOf(0x03, 0x08, 0x10, 0x00)

    /** How often [KEEP_AWAKE_COMMAND] is repeated: within the shortest power save time. */
    const val KEEP_AWAKE_INTERVAL_MS = 5_000L

    // GPS enable command bytes
    val GPS_ENABLE_COMMAND = byteArrayOf(0x01)

    // DD01 notifications verified
    val LOCATION_TRANSFER_DISABLED = byteArrayOf(0x03, 0x01, 0x02, 0x00)
    val LOCATION_TRANSFER_AVAILABLE = byteArrayOf(0x03, 0x01, 0x03, 0x01)
    val LOCATION_LOCK_RELEASE_COMMAND = byteArrayOf(0x00)

    // remote control commands (see tools/sony_shutter/intervalometer.py)
    val FULL_SHUTTER_DOWN_COMMAND = byteArrayOf(0x01, 0x09)
    val FULL_SHUTTER_UP_COMMAND = byteArrayOf(0x01, 0x08)
    val HALF_SHUTTER_DOWN_COMMAND = byteArrayOf(0x01, 0x07)
    val HALF_SHUTTER_UP_COMMAND = byteArrayOf(0x01, 0x06)
    val AF_ON_DOWN_COMMAND = byteArrayOf(0x01, 0x15)
    val AF_ON_UP_COMMAND = byteArrayOf(0x01, 0x14)

    // Same bytes as HALF_SHUTTER_UP — a released half-press acts as a harmless status probe
    val PROBE_COMMAND = byteArrayOf(0x01, 0x06)

    /** Remote status payload: camera idle/ready (also acks a full press → auto shutter-up). */
    val STATUS_READY = byteArrayOf(0x02, 0xA0.toByte(), 0x00)

    /** Remote status payload: shutter active — the exposure is running. */
    val STATUS_SHUTTER_ACTIVE = byteArrayOf(0x02, 0xA0.toByte(), 0x20)

    /** Remote status payload: focus acquired after a half press. */
    val STATUS_FOCUS_ACQUIRED = byteArrayOf(0x02, 0x3F, 0x20)

    // Location update interval: the periodic send tick and the default for a Sony camera
    const val LOCATION_UPDATE_INTERVAL_MS = 5000L

    /** Seconds between location updates for a Sony camera, as offered in its details. */
    val SEND_INTERVALS_SECONDS = listOf(5, 10, 15, 20, 30)

    /** Default and recommended: the app's interval before it could be changed. */
    const val SEND_INTERVAL_SECONDS = 5

    // Accuracy threshold for location updates
    const val ACCURACY_THRESHOLD_METERS = 200.0

    const val locationTransmissionNotificationId = 404
}
