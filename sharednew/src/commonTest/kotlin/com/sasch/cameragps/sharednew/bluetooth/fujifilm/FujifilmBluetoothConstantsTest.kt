package com.sasch.cameragps.sharednew.bluetooth.fujifilm

import kotlin.test.Test
import kotlin.test.assertTrue
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants as Fuji

class FujifilmBluetoothConstantsTest {
    /** iOS discovers only the listed services, so every service the app uses must be in it. */
    @Test
    fun serviceListCoversEveryServiceTheAppUses() {
        val used = (Fuji.REQUIRED_SUBSCRIPTIONS + Fuji.OPTIONAL_SUBSCRIPTIONS).map { it.serviceUuid } +
                listOf(
                    Fuji.PAIR_SERVICE_UUID,
                    Fuji.NOTIFICATION_SERVICE_UUID,
                    Fuji.GEOTAG_SERVICE_UUID,
                    Fuji.TIME_SERVICE_UUID,
                    // Power switch and CONNECT WHILE POWER OFF.
                    Fuji.STARTUP_INFO_SERVICE_UUID,
                    // Detection of the legacy protocol.
                    Fuji.LEGACY_PAIR_SERVICE_UUID,
                )
        val missing = used.toSet() - Fuji.SERVICE_UUIDS.toSet()
        assertTrue(missing.isEmpty(), "Missing from SERVICE_UUIDS: $missing")
    }
}
