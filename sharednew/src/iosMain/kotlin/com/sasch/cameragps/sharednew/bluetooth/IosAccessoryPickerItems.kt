package com.sasch.cameragps.sharednew.bluetooth

import com.sasch.cameragps.sharednew.bluetooth.accessory.AccessoryCameraName
import com.sasch.cameragps.sharednew.bluetooth.accessory.PendingMigration
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants
import com.sasch.cameragps.sharednew.ui.devicelist.CameraBrand
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AccessorySetupKit.ASAccessorySupportBluetoothPairingLE
import platform.AccessorySetupKit.ASDiscoveryDescriptor
import platform.AccessorySetupKit.ASMigrationDisplayItem
import platform.AccessorySetupKit.ASPickerDisplayItem
import platform.Foundation.NSUUID
import platform.UIKit.UIImage

@OptIn(ExperimentalForeignApi::class)
internal object IosAccessoryPickerItems {

    /**
     * One item per brand; the picker lists every nearby camera that matches one. Both
     * use [AccessoryCameraName.FALLBACK], so a different accessory name can only come
     * from a system rename.
     */
    fun discovery(): List<ASPickerDisplayItem> = listOf(
        discoveryItem(sonyDescriptor()),
        discoveryItem(fujifilmDescriptor()),
    )

    private fun discoveryItem(descriptor: ASDiscoveryDescriptor) = ASPickerDisplayItem(
        name = AccessoryCameraName.FALLBACK,
        productImage = productImage(),
        descriptor = descriptor,
        // Temporarily use the standard setup flow without a rename step while
        // testing the extra system scan confirmation. Existing accessories can
        // still be renamed separately.
    )//.apply {
        // On iOS 26.1+ Swift replaces the initial label with the advertised name.
        // Older systems still offer the native rename step with this fallback.
    // setSetupOptions(ASPickerDisplayItemSetupRename)
    // }

    fun migration(candidates: List<PendingMigration>): List<ASMigrationDisplayItem> {
        val image = productImage()
        // Only Sony cameras were saved before AccessorySetupKit.
        return candidates.map { candidate ->
            ASMigrationDisplayItem(
                name = candidate.displayName,
                productImage = image,
                descriptor = sonyDescriptor(),
            ).apply {
                setPeripheralIdentifier(NSUUID(uUIDString = candidate.identifier))
            }
        }
    }

    /** The brand of the picker item a camera was added with, from its company ID. */
    fun brandOf(companyIdentifier: Int): CameraBrand? = when (companyIdentifier) {
        SonyBluetoothConstants.COMPANY_ID -> CameraBrand.Sony
        FujifilmBluetoothConstants.COMPANY_ID -> CameraBrand.Fujifilm
        else -> null
    }

    private fun productImage(): UIImage = IosAccessoryArtwork.image

    private fun sonyDescriptor(): ASDiscoveryDescriptor = ASDiscoveryDescriptor().apply {
        // Sony does not advertise the location service UUID. Keep the existing
        // company-ID matcher, declared in NSAccessorySetupBluetoothCompanyIdentifiers.
        setBluetoothCompanyIdentifier(SonyBluetoothConstants.COMPANY_ID.toUShort())
        setSupportedOptions(ASAccessorySupportBluetoothPairingLE)
    }

    /**
     * Without Bluetooth pairing on purpose: the picker would pair on a connection of
     * its own and end it, but a Fujifilm camera has to be set up (registered) on the
     * connection that pairs it, or it refuses the phone for minutes. The app's first
     * connection pairs instead: iOS asks to confirm the code the camera shows.
     */
    private fun fujifilmDescriptor(): ASDiscoveryDescriptor = ASDiscoveryDescriptor().apply {
        setBluetoothCompanyIdentifier(FujifilmBluetoothConstants.COMPANY_ID.toUShort())
    }
}
