package com.sasch.cameragps.sharednew.ui.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.always_on_description
import cameragps.sharednew.generated.resources.always_on_short
import cameragps.sharednew.generated.resources.auto_area_adjustment
import cameragps.sharednew.generated.resources.auto_area_adjustment_hint
import cameragps.sharednew.generated.resources.auto_area_adjustment_short
import cameragps.sharednew.generated.resources.auto_time_correction
import cameragps.sharednew.generated.resources.auto_time_correction_hint
import cameragps.sharednew.generated.resources.auto_time_correction_short
import cameragps.sharednew.generated.resources.camera_setting_connect
import cameragps.sharednew.generated.resources.camera_setting_failed
import cameragps.sharednew.generated.resources.camera_setting_pending
import cameragps.sharednew.generated.resources.camera_setting_retry
import cameragps.sharednew.generated.resources.camera_setting_unsupported
import cameragps.sharednew.generated.resources.cancel_button
import cameragps.sharednew.generated.resources.delete_device
import cameragps.sharednew.generated.resources.detail_section_advanced
import cameragps.sharednew.generated.resources.detail_section_general
import cameragps.sharednew.generated.resources.detail_section_location
import cameragps.sharednew.generated.resources.detail_section_power
import cameragps.sharednew.generated.resources.detail_section_remote
import cameragps.sharednew.generated.resources.detail_section_standby
import cameragps.sharednew.generated.resources.detail_status_away
import cameragps.sharednew.generated.resources.detail_status_away_hint
import cameragps.sharednew.generated.resources.detail_status_connecting
import cameragps.sharednew.generated.resources.detail_status_connecting_hint
import cameragps.sharednew.generated.resources.detail_status_disabled
import cameragps.sharednew.generated.resources.detail_status_disabled_hint
import cameragps.sharednew.generated.resources.detail_status_receiving
import cameragps.sharednew.generated.resources.detail_status_receiving_hint
import cameragps.sharednew.generated.resources.detail_status_standby
import cameragps.sharednew.generated.resources.detail_status_sync_off
import cameragps.sharednew.generated.resources.detail_status_switched_off
import cameragps.sharednew.generated.resources.detail_status_switched_off_hint
import cameragps.sharednew.generated.resources.detail_status_sync_off_hint
import cameragps.sharednew.generated.resources.dialog_ok
import cameragps.sharednew.generated.resources.enableConstantly
import cameragps.sharednew.generated.resources.enable_device
import cameragps.sharednew.generated.resources.enable_device_description
import cameragps.sharednew.generated.resources.enable_remote_control
import cameragps.sharednew.generated.resources.fujifilm_connect_while_off
import cameragps.sharednew.generated.resources.fujifilm_connect_while_off_hint
import cameragps.sharednew.generated.resources.fujifilm_connect_while_off_short
import cameragps.sharednew.generated.resources.fujifilm_location_interval_hint
import cameragps.sharednew.generated.resources.fujifilm_location_sync
import cameragps.sharednew.generated.resources.fujifilm_location_sync_hint
import cameragps.sharednew.generated.resources.fujifilm_location_sync_short
import cameragps.sharednew.generated.resources.fujifilm_standby_hint
import cameragps.sharednew.generated.resources.fujifilm_standby_interval
import cameragps.sharednew.generated.resources.fujifilm_standby_interval_hint
import cameragps.sharednew.generated.resources.fujifilm_time_sync
import cameragps.sharednew.generated.resources.fujifilm_time_sync_hint
import cameragps.sharednew.generated.resources.fujifilm_time_sync_short
import cameragps.sharednew.generated.resources.handshake_delay_description
import cameragps.sharednew.generated.resources.handshake_delay_off
import cameragps.sharednew.generated.resources.handshake_delay_seconds
import cameragps.sharednew.generated.resources.handshake_delay_title
import cameragps.sharednew.generated.resources.hint_if_issues_after_switching
import cameragps.sharednew.generated.resources.info_24px
import cameragps.sharednew.generated.resources.interval_minutes
import cameragps.sharednew.generated.resources.interval_recommendation
import cameragps.sharednew.generated.resources.interval_recommended
import cameragps.sharednew.generated.resources.interval_seconds
import cameragps.sharednew.generated.resources.interval_short_minutes
import cameragps.sharednew.generated.resources.interval_short_seconds
import cameragps.sharednew.generated.resources.location_interval
import cameragps.sharednew.generated.resources.location_linking_disabled_by_camera
import cameragps.sharednew.generated.resources.remote_control_hint
import cameragps.sharednew.generated.resources.remote_control_short
import cameragps.sharednew.generated.resources.rename_camera_hint
import cameragps.sharednew.generated.resources.rename_camera_label
import cameragps.sharednew.generated.resources.rename_camera_save
import cameragps.sharednew.generated.resources.rename_camera_title
import cameragps.sharednew.generated.resources.setting_info
import cameragps.sharednew.generated.resources.sony_keep_awake
import cameragps.sharednew.generated.resources.sony_keep_awake_hint
import cameragps.sharednew.generated.resources.sony_keep_awake_short
import cameragps.sharednew.generated.resources.sony_location_interval_hint
import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.fujifilm.FujifilmBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.session.CameraAutoCorrectionSetting
import com.sasch.cameragps.sharednew.bluetooth.session.CameraProtocol
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSettingState
import com.sasch.cameragps.sharednew.ui.AttentionAmber
import com.sasch.cameragps.sharednew.ui.AwayRed
import com.sasch.cameragps.sharednew.ui.DisabledGrey
import com.sasch.cameragps.sharednew.ui.ReceivingGreen
import com.sasch.cameragps.sharednew.ui.StandbyBlue
import com.sasch.cameragps.sharednew.ui.components.ScrollbarLazyColumn
import com.sasch.cameragps.sharednew.ui.devicelist.CameraBrand
import com.sasch.cameragps.sharednew.ui.devicelist.DeleteDeviceDialog
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsCard
import com.sasch.cameragps.sharednew.util.KotlinPlatform
import com.sasch.cameragps.sharednew.util.currentPlatform
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Shared camera details: a status card, then the settings grouped in cards like the app's
 * Settings screen. Platform hosts wrap this with their own scaffold/toolbar.
 */
@Composable
fun DeviceDetailContent(
    viewModel: DeviceDetailViewModel,
    deviceId: String,
    deviceName: String? = null,
    modifier: Modifier = Modifier,
    onDeviceEnabledChanged: ((Boolean) -> Unit)? = null,
    onPresentSystemRename: (() -> Unit)? = null,
    renameEnabled: Boolean = true,
    /** The brand seen when the camera was added; null when unknown. */
    brand: CameraBrand? = null,
    /** Brand and model, e.g. "Fujifilm X100VI", shown in the status card. */
    modelLine: String? = null,
    /** Removes the camera; offered at the bottom, after a confirmation. */
    onRemove: (() -> Unit)? = null,
) {
    val state = viewModel.uiState.collectAsState().value
    val sessions by viewModel.sessions.collectAsState()
    val session = sessions[deviceId.uppercase()]
    val cameraReady = session?.phase == BleSessionPhase.Transmitting
    val fujifilm = brand == CameraBrand.Fujifilm ||
            session?.protocol == CameraProtocol.FujifilmSecure
    val controlsEnabled = state.isDeviceEnabled && state.buttonEnabled
    val name = state.deviceName.ifEmpty { deviceName.orEmpty() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshCameraSettings(deviceId)
    }

    // Reload on a name change too: an iOS system rename lands asynchronously,
    // through the accessory snapshot rather than through this screen.
    LaunchedEffect(deviceId, deviceName) {
        viewModel.load(deviceId, deviceName)
    }

    @Composable
    fun CameraSetting(setting: CameraAutoCorrectionSetting, title: StringResource, short: StringResource, hint: StringResource) {
        CameraSettingRow(
            title = stringResource(title),
            description = stringResource(short),
            infoText = stringResource(hint),
            state = session?.autoCorrectionSetting(setting) ?: CameraSettingState(),
            cameraReady = cameraReady,
            onCheckedChange = { viewModel.setAutoCorrectionSetting(deviceId, setting, it) },
            onRetry = { viewModel.refreshCameraSettings(deviceId) },
        )
    }

    ScrollbarLazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "status") {
            CameraStatusCard(
                status = detailStatus(state.isDeviceEnabled, session),
                details = listOfNotNull(
                    modelLine,
                    deviceId.takeIf { currentPlatform == KotlinPlatform.Android },
                ).joinToString(" · ").ifEmpty { null },
            )
        }

        item(key = "general") {
            SharedSettingsCard(title = stringResource(Res.string.detail_section_general)) {
                if (renameEnabled) {
                    DeviceRenameRow(
                        currentName = name,
                        onPresentSystemRename = onPresentSystemRename,
                        onRenameInApp = { newName -> viewModel.renameDevice(deviceId, newName) },
                    )
                    RowDivider()
                }
                SwitchRow(
                    title = stringResource(Res.string.enable_device),
                    description = stringResource(Res.string.enable_device_description),
                    checked = state.isDeviceEnabled,
                    enabled = !state.isAlwaysOnEnabled && state.buttonEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setDeviceEnabled(enabled, deviceId, onDeviceEnabledChanged)
                    },
                )
                if (currentPlatform == KotlinPlatform.Android) {
                    RowDivider()
                    SwitchRow(
                        title = stringResource(Res.string.enableConstantly),
                        description = stringResource(Res.string.always_on_short),
                        infoText = stringResource(Res.string.always_on_description) +
                                "\n\n" + stringResource(Res.string.hint_if_issues_after_switching),
                        checked = state.isAlwaysOnEnabled,
                        enabled = controlsEnabled,
                        onCheckedChange = { enabled -> viewModel.setAlwaysOnEnabled(enabled, deviceId) },
                    )
                }
            }
        }

        if (fujifilm) {
            item(key = "location") {
                SharedSettingsCard(title = stringResource(Res.string.detail_section_location)) {
                    CameraSetting(
                        CameraAutoCorrectionSetting.FujifilmLocationSync,
                        Res.string.fujifilm_location_sync,
                        Res.string.fujifilm_location_sync_short,
                        Res.string.fujifilm_location_sync_hint,
                    )
                    RowDivider()
                    IntervalSetting(
                        title = stringResource(Res.string.location_interval),
                        infoText = stringResource(Res.string.fujifilm_location_interval_hint),
                        choices = FujifilmBluetoothConstants.GEOTAG_SYNC_INTERVALS_SECONDS,
                        recommended = FujifilmBluetoothConstants.GEOTAG_SYNC_INTERVAL_SECONDS,
                        selected = state.locationIntervalS,
                        enabled = controlsEnabled,
                        onSelected = { viewModel.setLocationInterval(it, deviceId) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(Res.string.fujifilm_time_sync),
                        description = stringResource(Res.string.fujifilm_time_sync_short),
                        infoText = stringResource(Res.string.fujifilm_time_sync_hint),
                        checked = state.isTimeSyncEnabled,
                        enabled = controlsEnabled,
                        onCheckedChange = { enabled -> viewModel.setTimeSyncEnabled(enabled, deviceId) },
                    )
                }
            }
            item(key = "standby") {
                SharedSettingsCard(title = stringResource(Res.string.detail_section_standby)) {
                    CameraSetting(
                        CameraAutoCorrectionSetting.FujifilmConnectWhileOff,
                        Res.string.fujifilm_connect_while_off,
                        Res.string.fujifilm_connect_while_off_short,
                        Res.string.fujifilm_connect_while_off_hint,
                    )
                    RowDivider()
                    IntervalSetting(
                        title = stringResource(Res.string.fujifilm_standby_interval),
                        infoText = stringResource(Res.string.fujifilm_standby_interval_hint),
                        choices = FujifilmBluetoothConstants.STANDBY_INTERVALS_SECONDS,
                        recommended = FujifilmBluetoothConstants.STANDBY_INTERVAL_SECONDS,
                        selected = state.standbyIntervalS,
                        enabled = controlsEnabled,
                        onSelected = { viewModel.setStandbyInterval(it, deviceId) },
                    )
                }
            }
        } else {
            item(key = "location") {
                SharedSettingsCard(title = stringResource(Res.string.detail_section_location)) {
                    IntervalSetting(
                        title = stringResource(Res.string.location_interval),
                        infoText = stringResource(Res.string.sony_location_interval_hint),
                        choices = SonyBluetoothConstants.SEND_INTERVALS_SECONDS,
                        recommended = SonyBluetoothConstants.SEND_INTERVAL_SECONDS,
                        selected = state.sendIntervalS,
                        enabled = controlsEnabled,
                        onSelected = { viewModel.setSendInterval(it, deviceId) },
                    )
                    RowDivider()
                    CameraSetting(
                        CameraAutoCorrectionSetting.Time,
                        Res.string.auto_time_correction,
                        Res.string.auto_time_correction_short,
                        Res.string.auto_time_correction_hint,
                    )
                    RowDivider()
                    CameraSetting(
                        CameraAutoCorrectionSetting.Area,
                        Res.string.auto_area_adjustment,
                        Res.string.auto_area_adjustment_short,
                        Res.string.auto_area_adjustment_hint,
                    )
                }
            }
            item(key = "power") {
                SharedSettingsCard(title = stringResource(Res.string.detail_section_power)) {
                    SwitchRow(
                        title = stringResource(Res.string.sony_keep_awake),
                        description = stringResource(Res.string.sony_keep_awake_short),
                        infoText = stringResource(Res.string.sony_keep_awake_hint),
                        checked = state.isKeepAwakeEnabled,
                        enabled = controlsEnabled,
                        onCheckedChange = { enabled -> viewModel.setKeepAwakeEnabled(enabled, deviceId) },
                    )
                }
            }
            item(key = "remote") {
                SharedSettingsCard(title = stringResource(Res.string.detail_section_remote)) {
                    SwitchRow(
                        title = stringResource(Res.string.enable_remote_control),
                        description = stringResource(Res.string.remote_control_short),
                        infoText = stringResource(Res.string.remote_control_hint),
                        checked = state.isRemoteControlEnabled,
                        enabled = controlsEnabled,
                        onCheckedChange = { enabled -> viewModel.setRemoteControlStatus(enabled, deviceId) },
                    )
                }
            }
        }

        item(key = "advanced") {
            SharedSettingsCard(title = stringResource(Res.string.detail_section_advanced)) {
                HandshakeDelaySetting(
                    delayMs = state.handshakeDelayMs,
                    enabled = controlsEnabled,
                    onDelayChanged = { delayMs -> viewModel.setHandshakeDelay(delayMs, deviceId) },
                )
            }
        }

        if (onRemove != null) {
            item(key = "remove") { RemoveCameraButton(name = name, onRemove = onRemove) }
        }
    }
}

// ---- Status ----

private enum class DetailStatus { Disabled, Receiving, Standby, SyncOff, LinkingOff, Connecting, SwitchedOff, Away }

private fun detailStatus(enabled: Boolean, session: CameraSession?): DetailStatus = when {
    !enabled -> DetailStatus.Disabled
    session == null -> DetailStatus.Away
    session.cameraOff -> DetailStatus.SwitchedOff
    session.isLocationReady && !session.wantsLocation -> DetailStatus.SyncOff
    session.isLocationReady && session.locationDisabledByCamera -> DetailStatus.LinkingOff
    session.isLocationReady && session.inStandby -> DetailStatus.Standby
    session.takesLocation -> DetailStatus.Receiving
    else -> DetailStatus.Connecting
}

/** What the camera is doing now: a colored dot, a short title and one explaining line. */
@Composable
private fun CameraStatusCard(status: DetailStatus, details: String?) {
    val (color, title, hint) = when (status) {
        DetailStatus.Receiving -> Triple(ReceivingGreen, Res.string.detail_status_receiving, Res.string.detail_status_receiving_hint)
        DetailStatus.Standby -> Triple(StandbyBlue, Res.string.detail_status_standby, Res.string.fujifilm_standby_hint)
        DetailStatus.SyncOff -> Triple(AttentionAmber, Res.string.detail_status_sync_off, Res.string.detail_status_sync_off_hint)
        DetailStatus.LinkingOff -> Triple(AttentionAmber, Res.string.detail_status_sync_off, Res.string.location_linking_disabled_by_camera)
        DetailStatus.Connecting -> Triple(AttentionAmber, Res.string.detail_status_connecting, Res.string.detail_status_connecting_hint)
        DetailStatus.SwitchedOff -> Triple(AwayRed, Res.string.detail_status_switched_off, Res.string.detail_status_switched_off_hint)
        DetailStatus.Away -> Triple(AwayRed, Res.string.detail_status_away, Res.string.detail_status_away_hint)
        DetailStatus.Disabled -> Triple(DisabledGrey, Res.string.detail_status_disabled, Res.string.detail_status_disabled_hint)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(12.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (details != null) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---- Rows ----

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One setting: a title (with an optional info button for the long explanation) and a
 * short description on the left, the control on the right.
 */
@Composable
private fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    infoText: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            SettingTitle(title = title, infoText = infoText, enabled = enabled)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = descriptionColor,
                )
            }
        }
        trailing?.invoke()
    }
}

private const val INFO_ICON_ID = "info"

/**
 * A setting's title. The info icon, which opens the long explanation, is part of the text,
 * so it stays after the last word when the title wraps.
 */
@Composable
private fun SettingTitle(title: String, infoText: String?, enabled: Boolean) {
    var showInfo by remember { mutableStateOf(false) }
    Text(
        text = buildAnnotatedString {
            append(title)
            if (infoText != null) {
                // A no-break space keeps the icon with the last word when the title wraps;
                // the icon has its own description, so screen readers skip the placeholder.
                append('\u00A0')
                appendInlineContent(INFO_ICON_ID, "\u200B")
            }
        },
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        inlineContent = if (infoText == null) {
            emptyMap()
        } else {
            mapOf(
                INFO_ICON_ID to InlineTextContent(
                    Placeholder(
                        width = 1.25.em,
                        height = 1.25.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    // Stays tappable on a disabled setting: the explanation says why.
                    Icon(
                        painterResource(Res.drawable.info_24px),
                        contentDescription = stringResource(Res.string.setting_info),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { showInfo = true },
                    )
                },
            )
        },
    )
    if (showInfo && infoText != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(title) },
            text = { Text(infoText) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(Res.string.dialog_ok))
                }
            },
        )
    }
}

/** A [SettingRow] with a switch; the whole row toggles it, not just the switch. */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    infoText: String? = null,
) {
    SettingRow(
        title = title,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        description = description,
        descriptionColor = descriptionColor,
        infoText = infoText,
        enabled = enabled,
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A setting stored on the camera: its status (connect first, pending, failed) replaces the description. */
@Composable
private fun CameraSettingRow(
    title: String,
    description: String,
    infoText: String,
    state: CameraSettingState,
    cameraReady: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val enabled = cameraReady && state.supported == true && state.enabled != null && !state.pending
    val failed = cameraReady && state.failed && !state.pending
    val status = when {
        !cameraReady -> Res.string.camera_setting_connect
        state.pending -> Res.string.camera_setting_pending
        state.supported == false -> Res.string.camera_setting_unsupported
        state.failed -> Res.string.camera_setting_failed
        state.enabled == null -> Res.string.camera_setting_pending
        else -> null
    }
    Column {
        SwitchRow(
            title = title,
            checked = state.enabled == true,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            description = status?.let { stringResource(it) } ?: description,
            descriptionColor = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            infoText = infoText,
        )
        if (failed) {
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(Res.string.camera_setting_retry))
            }
        }
    }
}

/** A slider setting: the chosen value as the description, an optional note, the slider below. */
@Composable
private fun SliderSetting(
    title: String,
    infoText: String?,
    value: String,
    note: String?,
    enabled: Boolean,
    position: Float,
    steps: Int,
    range: ClosedFloatingPointRange<Float>,
    onPositionChange: (Float) -> Unit,
    onPositionChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            title = title,
            description = value,
            descriptionColor = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            infoText = infoText,
            enabled = enabled,
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = position,
            onValueChange = onPositionChange,
            onValueChangeFinished = onPositionChangeFinished,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

/** Picks one of [choices] (seconds); the recommended one is marked, or named when another is picked. */
@Composable
private fun IntervalSetting(
    title: String,
    infoText: String,
    choices: List<Int>,
    recommended: Int,
    selected: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
) {
    val selectedIndex = choices.indexOf(selected).takeIf { it >= 0 } ?: choices.indexOf(recommended)
    // Local value while dragging; saved only on release.
    var position by remember(selectedIndex) { mutableStateOf(selectedIndex.toFloat()) }
    val seconds = choices[position.roundToInt().coerceIn(choices.indices)]
    val every = if (seconds < 60) {
        stringResource(Res.string.interval_seconds, seconds)
    } else {
        stringResource(Res.string.interval_minutes, seconds / 60)
    }
    val recommendedShort = if (recommended < 60) {
        stringResource(Res.string.interval_short_seconds, recommended)
    } else {
        stringResource(Res.string.interval_short_minutes, recommended / 60)
    }
    SliderSetting(
        title = title,
        infoText = infoText,
        value = if (seconds == recommended) stringResource(Res.string.interval_recommended, every) else every,
        note = if (seconds == recommended) null else stringResource(Res.string.interval_recommendation, recommendedShort),
        enabled = enabled,
        position = position,
        steps = choices.size - 2,
        range = 0f..(choices.size - 1).toFloat(),
        onPositionChange = { position = it },
        onPositionChangeFinished = { onSelected(choices[position.roundToInt().coerceIn(choices.indices)]) },
    )
}

@Composable
private fun HandshakeDelaySetting(
    delayMs: Long,
    enabled: Boolean,
    onDelayChanged: (Long) -> Unit,
) {
    // Local value while dragging; persisted only on release
    var sliderSeconds by remember(delayMs) { mutableStateOf((delayMs / 1000L).toFloat()) }
    val seconds = sliderSeconds.roundToInt()
    SliderSetting(
        title = stringResource(Res.string.handshake_delay_title),
        infoText = stringResource(Res.string.handshake_delay_description),
        value = if (seconds == 0) {
            stringResource(Res.string.handshake_delay_off)
        } else {
            stringResource(Res.string.handshake_delay_seconds, seconds)
        },
        note = null,
        enabled = enabled,
        position = sliderSeconds,
        steps = 9,
        range = 0f..10f,
        onPositionChange = { sliderSeconds = it },
        onPositionChangeFinished = { onDelayChanged(sliderSeconds.roundToInt() * 1000L) },
    )
}

/**
 * Rename affordance. When the platform can rename the camera in the system's own
 * accessory record it presents that sheet instead of the in-app dialog, so the
 * two never disagree; the app-side dialog is used everywhere else.
 */
@Composable
private fun DeviceRenameRow(
    currentName: String,
    onPresentSystemRename: (() -> Unit)?,
    onRenameInApp: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    SettingRow(
        title = stringResource(Res.string.rename_camera_label),
        description = currentName.ifEmpty { null },
    ) {
        TextButton(
            onClick = {
                if (onPresentSystemRename != null) onPresentSystemRename() else showDialog = true
            },
        ) {
            Text(stringResource(Res.string.rename_camera_title))
        }
    }

    if (showDialog) {
        RenameCameraDialog(
            currentName = currentName,
            onDismiss = { showDialog = false },
            onConfirm = { newName ->
                showDialog = false
                onRenameInApp(newName)
            },
        )
    }
}

@Composable
private fun RenameCameraDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.rename_camera_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.rename_camera_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.rename_camera_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            // A blank name would erase the camera's identity in the list.
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(Res.string.rename_camera_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel_button))
            }
        },
    )
}

/** Removing is destructive: a quiet outlined button at the end, confirmed like a swipe in the list. */
@Composable
private fun RemoveCameraButton(name: String, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { confirm = true },
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(Res.string.delete_device))
    }
    if (confirm) {
        DeleteDeviceDialog(
            name = name,
            onConfirm = {
                confirm = false
                onRemove()
            },
            onDismiss = { confirm = false },
        )
    }
}
