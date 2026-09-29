package com.sasch.cameragps.sharednew.database.devices

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "camera_devices")
data class CameraDevice(
    @PrimaryKey(autoGenerate = false)
    val mac: String,
    @ColumnInfo(defaultValue = "1")
    val deviceEnabled: Boolean = true,
    val alwaysOnEnabled: Boolean = false,
    val deviceName: String = "N/A",
    /**
     * True once a person named this camera, either in the app or through the
     * iOS system rename sheet. A custom name is never replaced by a hardware
     * name; a derived one is upgraded as soon as a better hardware name appears.
     */
    @ColumnInfo(defaultValue = "0")
    val deviceNameIsCustom: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val remoteControlEnabled: Boolean = false,
    /**
     * Wait this long after connecting before starting discovery + handshake.
     * Workaround for cameras that stall their own boot while servicing the
     * BLE traffic burst (reported on A7R IV). 0 = start immediately.
     */
    @ColumnInfo(defaultValue = "0")
    val handshakeDelayMs: Long = 0,
    /** Fujifilm: set the camera's date, time and time zone from the phone on connect. */
    @ColumnInfo(defaultValue = "1")
    val timeSyncEnabled: Boolean = true,
    /** Fujifilm: seconds between the camera's location requests while it is awake. */
    @ColumnInfo(defaultValue = "10")
    val locationIntervalS: Int = 10,
    /** Fujifilm: seconds between the phone's location fixes while the camera is in standby. */
    @ColumnInfo(defaultValue = "60")
    val standbyIntervalS: Int = 60,
)

