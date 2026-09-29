package com.sasch.cameragps.sharednew.bluetooth.transport

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import kotlin.test.Test
import kotlin.test.assertEquals

class BleUuidsTest {
    @Test
    fun shortFormsExpandToTheBluetoothBaseUuid() {
        assertEquals("00002a05-0000-1000-8000-00805f9b34fb", BleUuids.normalize("2A05"))
        assertEquals("00002a05-0000-1000-8000-00805f9b34fb", BleUuids.normalize("00002A05"))
    }

    @Test
    fun fullUuidsOnlyChangeCase() {
        assertEquals(
            "f557d96b-8284-4667-8793-b971c1deca2a",
            BleUuids.normalize(" F557D96B-8284-4667-8793-B971C1DECA2A "),
        )
        assertEquals(
            SonyBluetoothConstants.CHARACTERISTIC_UUID.lowercase(),
            BleUuids.normalize(SonyBluetoothConstants.CHARACTERISTIC_UUID),
        )
    }
}
