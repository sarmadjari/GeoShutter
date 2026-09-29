package com.sasch.cameragps.sharednew

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.baseline_view_list_24
import cameragps.sharednew.generated.resources.header_device_list
import cameragps.sharednew.generated.resources.info_24px
import cameragps.sharednew.generated.resources.settings_24px
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.session.CameraAutoCorrectionControls
import com.sasch.cameragps.sharednew.bluetooth.session.CameraAutoCorrectionSetting
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailContent
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailDataSource
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailServiceActions
import com.sasch.cameragps.sharednew.ui.device.DeviceDetailViewModel
import com.sasch.cameragps.sharednew.ui.device.SharedDevicesScreen
import com.sasch.cameragps.sharednew.ui.devicelist.CameraBrand
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListItem
import com.sasch.cameragps.sharednew.ui.pairing.SharedPairingPreparationScreen
import com.sasch.cameragps.sharednew.ui.theme.CameraGpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.UIKit.UIViewController

/**
 * Real production composables with deterministic sample data and inert callbacks.
 * The Swift host exposes this entry point only in Debug simulator builds. It never
 * constructs the Bluetooth controller, requests permissions or starts diagnostics.
 */
@Suppress("FunctionName", "unused")
fun StoreScreenshotViewController(scenario: String): UIViewController {
    require(scenario in setOf("geotagging", "reconnect", "remote", "multiple", "privacy", "pairing", "details"))
    return ComposeUIViewController {
        CameraGpsTheme(darkTheme = false) {
            if (scenario == "details") {
                // A Sony camera that GeoShutter keeps waking: the status card's tip.
                val viewModel = remember {
                    DeviceDetailViewModel(PreviewDetailData, PreviewDetailActions, PreviewWakeLoopControls)
                }
                Surface(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DeviceDetailContent(
                        viewModel = viewModel,
                        deviceId = PREVIEW_SONY_ID,
                        deviceName = "ILCE-1M2",
                        brand = CameraBrand.Sony,
                        modelLine = "Sony α1 II",
                    )
                }
            } else if (scenario == "pairing") {
                SharedPairingPreparationScreen(
                    isSearching = false,
                    onSearch = {},
                    onBack = {},
                    onHelp = {},
                )
            } else if (scenario == "privacy") {
                IosSettingsScreen(
                    isAppEnabled = true,
                    hapticsEnabled = true,
                    transmissionNotificationsEnabled = true,
                    transmissionNotificationsPermissionDenied = false,
                    onTransmissionNotificationsEnabledChange = {},
                    onOpenNotificationSettings = {},
                    onBackClick = {},
                    onAppEnabledChange = {},
                    onHapticsEnabledChange = {},
                    liveActivityEnabled = true,
                    onLiveActivityEnabledChange = {},
                    sentryEnabled = false,
                    onSentryEnabledChange = {},
                    onChangeLogLevel = {},
                    // The store screenshot shows the optional error reporting.
                    crashReportingAvailable = true,
                )
            } else {
                // Share the long fixture with SCREENSHOT_MODE so both entry points
                // exercise scrolling; remote keeps its single-camera composition.
                val sampleDevices = if (scenario == "remote") mockDevices.take(1) else mockDevices
                val devices = sampleDevices.mapIndexed { index, device ->
                    device.copy(
                        isSaved = true,
                        isConnected = scenario != "reconnect" || index == 0,
                    )
                }
                val items = devices.associate { device ->
                    device.identifier to DeviceListItem(
                        identifier = device.identifier,
                        customName = null,
                        isAlwaysOnEnabled = false,
                        isTransmissionActive = device.isConnected,
                        isRemoteFeatureActive = device.isConnected,
                        isShutterActive = false,
                    )
                }
                SharedDevicesScreen(
                    title = stringResource(Res.string.header_device_list),
                    topBarActions = {
                        for (icon in listOf(Res.drawable.info_24px, Res.drawable.baseline_view_list_24, Res.drawable.settings_24px)) {
                            IconButton(onClick = {}) {
                                Icon(painterResource(icon), contentDescription = null)
                            }
                        }
                    },
                ) {
                    DeviceListContent(
                        devices = devices,
                        items = items,
                        isAppEnabled = true,
                        hapticsEnabled = false,
                        migrationCandidates = emptyList(),
                        onMigrate = {},
                        onAddCamera = {},
                        onOpenSettings = {},
                        onOpenHelp = {},
                        onConnect = {},
                        onTriggerRemoteShutter = {},
                        onDelete = {},
                        onOpenDetails = {},
                    )
                }
            }
        }
    }
}

private const val PREVIEW_SONY_ID = "1AB33850-C251-7695-1F88-295381C8C884"

private object PreviewDetailData : DeviceDetailDataSource {
    override suspend fun ensureDeviceExists(deviceId: String, deviceName: String?) = Unit
    override suspend fun isDeviceEnabled(deviceId: String) = true
    override suspend fun isAlwaysOnEnabled(deviceId: String) = false
    override suspend fun isRemoteControlEnabled(deviceId: String) = false
    override suspend fun getHandshakeDelayMs(deviceId: String) = 0L
    override suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean) = Unit
    override suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean) = Unit
    override suspend fun setRemoteControlEnabled(deviceId: String, enabled: Boolean) = Unit
    override suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long) = Unit
    override suspend fun getDeviceName(deviceId: String) = "ILCE-1M2"
    override suspend fun setDeviceName(deviceId: String, name: String) = Unit
}

private object PreviewDetailActions : DeviceDetailServiceActions {
    override fun startAlwaysOn(deviceAddress: String) = Unit
    override fun requestShutdown(deviceAddress: String) = Unit
    override fun setRemoteControlMonitoring(deviceAddress: String, enabled: Boolean) = Unit
}

private object PreviewWakeLoopControls : CameraAutoCorrectionControls {
    override val sessions: StateFlow<Map<String, CameraSession>> =
        MutableStateFlow(mapOf(PREVIEW_SONY_ID to CameraSession(PREVIEW_SONY_ID, BleSessionPhase.Transmitting)))
    override val wakeLoops: StateFlow<Set<String>> = MutableStateFlow(setOf(PREVIEW_SONY_ID))
    override fun refreshAutoCorrectionSettings(identifier: String) = Unit
    override fun setAutoCorrectionSetting(identifier: String, setting: CameraAutoCorrectionSetting, enabled: Boolean) = Unit
}
