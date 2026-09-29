package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryCameraName
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import com.sasch.cameragps.sharednew.ui.devicelist.CameraBrand
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AccessorySetupKit.ASAccessorySupportBluetoothPairingLE
import platform.UIKit.UIImageRenderingMode.UIImageRenderingModeAlwaysOriginal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosAccessoryPickerItemsTest {
    @Test
    fun discoveryAndMigrationUseTheSameHighResolutionOriginalArtwork() {
        val image = IosAccessoryArtwork.image
        // Kotlin/Native can wrap the same native UIImage with different wrappers.
        assertTrue(IosAccessoryPickerItems.discovery().all { it.productImage == image })
        val migration = IosAccessoryPickerItems.migration(listOf(
            PendingMigration("00000000-0000-0000-0000-000000000001", "ILCE-6700"),
        ))
        assertEquals(image, migration.single().productImage)
        assertEquals(UIImageRenderingModeAlwaysOriginal, image.renderingMode)
        image.size.useContents {
            assertTrue(width * image.scale >= 540, "Artwork must cover a 180 pt frame at 3×")
            assertTrue(height * image.scale >= 360, "Artwork must cover a 120 pt frame at 3×")
        }
    }

    @Test
    fun discoverySkipsExtraSetupStepsWithoutChangingTheSonyMatcherOrPairing() {
        val sony = IosAccessoryPickerItems.discovery().first()
        assertEquals(0uL, sony.setupOptions)
        assertEquals(AccessoryCameraName.FALLBACK, sony.name)
        assertEquals(0x012Du.toUShort(), sony.descriptor.bluetoothCompanyIdentifier)
        assertEquals(ASAccessorySupportBluetoothPairingLE, sony.descriptor.supportedOptions)
    }

    /**
     * A Fujifilm camera must be registered on the connection that pairs it, so the
     * picker must not pair it on a connection of its own.
     */
    @Test
    fun discoveryOffersFujifilmCamerasAndLeavesPairingToTheApp() {
        val items = IosAccessoryPickerItems.discovery()
        assertEquals(2, items.size)
        val fujifilm = items.last()
        assertEquals(0uL, fujifilm.setupOptions)
        assertEquals(AccessoryCameraName.FALLBACK, fujifilm.name)
        assertEquals(0x04D8u.toUShort(), fujifilm.descriptor.bluetoothCompanyIdentifier)
        assertEquals(0uL, fujifilm.descriptor.supportedOptions)
    }

    @Test
    fun brandFollowsThePickerItemsCompanyIdentifier() {
        val items = IosAccessoryPickerItems.discovery()
        assertEquals(
            listOf(CameraBrand.Sony, CameraBrand.Fujifilm),
            items.map { IosAccessoryPickerItems.brandOf(it.descriptor.bluetoothCompanyIdentifier.toInt()) },
        )
        assertNull(IosAccessoryPickerItems.brandOf(0x004C))
    }

    @Test
    fun migrationKeepsSavedNamesAndIdentifiersWithoutAddingSetupSteps() {
        val cameras = listOf(
            PendingMigration("00000000-0000-0000-0000-000000000001", "ILCE-6700"),
            PendingMigration("00000000-0000-0000-0000-000000000002", "My travel camera"),
        )
        val items = IosAccessoryPickerItems.migration(cameras)
        assertEquals(cameras.map { it.displayName }, items.map { it.name })
        assertEquals(cameras.map { it.identifier }, items.map { it.peripheralIdentifier?.UUIDString })
        // A rename step must not turn the migration-only operation into visible
        // discovery/setup. The return type also excludes regular display items.
        assertTrue(items.all { it.setupOptions == 0uL })
        assertTrue(items.all { it.descriptor.bluetoothCompanyIdentifier == 0x012Du.toUShort() })
    }
}
