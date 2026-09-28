package com.saschl.cameragps.ui

import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.no_devices_message
import cameragps.sharednew.generated.resources.no_devices_title
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListViewModel
import com.sasch.cameragps.sharednew.ui.devicelist.CameraBrand
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListItem
import com.sasch.cameragps.sharednew.ui.devicelist.EmptyStateCard
import com.sasch.cameragps.sharednew.ui.devicelist.SharedDeviceList
import com.sasch.cameragps.sharednew.ui.devicelist.cameraModelLine
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.service.AssociatedDeviceCompat
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.ui.device.SCREENSHOT_MODE
import com.saschl.cameragps.ui.device.mockDeviceListItems
import com.saschl.cameragps.utils.PreferencesManager
import org.jetbrains.compose.resources.stringResource

/**
 * Android host for the shared device list: maps CDM associations to the shared
 * identity model and wires the callbacks back to CDM/service semantics.
 */
@Composable
fun AssociatedDevicesList(
    associatedDevices: List<AssociatedDeviceCompat>,
    onConnect: (AssociatedDeviceCompat) -> Unit,
    onDisassociate: (AssociatedDeviceCompat) -> Unit,
    onOpenTroubleshooting: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: DeviceListViewModel = viewModel {
        DeviceListViewModel(AndroidDeviceListDataSource(AppServices.from(context)))
    }
    val realItems by viewModel.items.collectAsState()
    val items = if (SCREENSHOT_MODE) mockDeviceListItems else realItems

    if (associatedDevices.isEmpty()) {
        EmptyStateCard(
            title = stringResource(Res.string.no_devices_title),
            message = stringResource(Res.string.no_devices_message),
        )
        return
    }

    val devices = associatedDevices.map { compat ->
        val item = items[compat.address.uppercase()]
        // A name the user chose wins, then the camera's own Bluetooth name; the
        // association (pairing) name is the model. CDM is left alone, so its own
        // record keeps whatever the pairing flow set.
        val name = item?.customName ?: item?.reportedName ?: compat.name
        BluetoothDeviceInfo(
            identifier = compat.address.uppercase(),
            name = name,
            // Drives the card's status line; mirrors the old "remote text only
            // while transmitting" gating
            isConnected = item?.isTransmissionActive == true,
            isSaved = true,
            isPaired = compat.isPaired,
            // Brand and model under the name: "Sony α1 II", "Fujifilm X100VI".
            model = cameraModelLine(compat.brand(item), compat.name)?.takeIf { it != name },
        )
    }

    fun resolve(info: BluetoothDeviceInfo): AssociatedDeviceCompat? =
        associatedDevices.firstOrNull { it.address.uppercase() == info.identifier }

    SharedDeviceList(
        devices = devices,
        items = items,
        showKeepAliveHint = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
        // Read on every recomposition so a toggle in settings applies on return
        hapticsEnabled = PreferencesManager.isHapticsEnabled(context),
        onConnect = { }, // tap-to-pair path: Android never lists unsaved devices
        onTriggerRemoteShutter = { info ->
            if (!SCREENSHOT_MODE) {
                val shutterIntent = Intent(
                    context.applicationContext,
                    LocationSenderService::class.java
                ).apply {
                    action = SonyBluetoothConstants.ACTION_TRIGGER_SHUTTER_SEQUENCE
                    putExtra("address", info.identifier)
                }
                context.startService(shutterIntent)
            }
        },
        onDelete = { info -> resolve(info)?.let(onDisassociate) },
        onOpenDetails = { info -> resolve(info)?.let(onConnect) },
        onOpenTroubleshooting = onOpenTroubleshooting,
    )
}

/**
 * The camera's maker, from the advertisement Android kept when the camera was added, or
 * from the live session (Fujifilm sessions have no Bluetooth remote).
 */
private fun AssociatedDeviceCompat.brand(item: DeviceListItem?): CameraBrand? = when {
    isFujifilm -> CameraBrand.Fujifilm
    isSony -> CameraBrand.Sony
    item?.remoteSupported == false -> CameraBrand.Fujifilm
    else -> null
}
