package com.sasch.cameragps.sharednew.bluetooth.transport

/** Bluetooth UUID strings in one comparable form. */
internal object BleUuids {
    private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /**
     * Lowercase 128-bit form. Short forms, as iOS reports Bluetooth SIG UUIDs ("2A05",
     * "00002A05"), are expanded with the Bluetooth base UUID.
     */
    fun normalize(uuid: String): String {
        val value = uuid.trim().lowercase()
        return when (value.length) {
            4 -> "0000$value$BASE_UUID_SUFFIX"
            8 -> "$value$BASE_UUID_SUFFIX"
            else -> value
        }
    }
}
