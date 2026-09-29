package com.saschl.cameragps.status

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sasch.cameragps.sharednew.status.CameraState
import com.sasch.cameragps.sharednew.status.CameraStatus
import com.sasch.cameragps.sharednew.status.GeoShutterStatus
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.MainActivity
import com.saschl.cameragps.R

/**
 * Home-screen widget, for monitoring only: whether GeoShutter is on, and each saved
 * camera with its name, brand and model and a dot (green sending, blue switched off in
 * standby but still receiving the location, amber connecting or location sync off on the
 * camera, red away, grey while GeoShutter is off). A tap opens the app.
 */
class StatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val publisher = AppServices.from(context).statusPublisher
        provideContent {
            val status by publisher.status.collectAsState()
            GlanceTheme { WidgetContent(context, status) }
        }
    }

    companion object {
        suspend fun refresh(context: Context) = StatusWidget().updateAll(context)
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}

private val Sending = ColorProvider(Color(0xFF2E9E4A))
private val Connecting = ColorProvider(Color(0xFFE8A317))
private val Away = ColorProvider(Color(0xFFD93025))
private val Off = ColorProvider(Color(0xFF9E9E9E))
private val SyncOffText = ColorProvider(Color(0xFFB26A00))
private val Standby = ColorProvider(Color(0xFF1E88E5))
private val StandbyText = ColorProvider(Color(0xFF1565C0))

@Composable
private fun WidgetContent(context: Context, status: GeoShutterStatus) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(
                    if (status.enabled && status.sending.isNotEmpty()) {
                        R.drawable.ic_gps_fixed
                    } else {
                        R.drawable.ic_gps_not_fixed
                    }
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(22.dp),
            )
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = context.getString(R.string.status_app_name),
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = GlanceTheme.colors.onSurface,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = summary(context, status),
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.height(10.dp))
        if (status.cameras.isEmpty()) {
            Text(
                text = context.getString(R.string.status_no_cameras),
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
        status.cameras.take(MAX_CAMERAS).forEach { camera ->
            CameraRow(context, camera, status.enabled)
            Spacer(GlanceModifier.height(6.dp))
        }
    }
}

@Composable
private fun CameraRow(context: Context, camera: CameraStatus, enabled: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(10.dp)
                .cornerRadius(5.dp)
                .background(
                    when {
                        !enabled -> Off
                        camera.state == CameraState.Sending -> Sending
                        camera.state == CameraState.Connecting -> Connecting
                        camera.state == CameraState.LocationSyncOff -> Connecting
                        camera.state == CameraState.CameraOff -> Standby
                        else -> Away
                    }
                ),
        ) {}
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = camera.name,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
            )
            camera.model?.let { model ->
                Text(
                    text = model,
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
            if (enabled && camera.state == CameraState.LocationSyncOff) {
                Text(
                    text = context.getString(R.string.status_sync_off_short),
                    style = TextStyle(fontSize = 12.sp, color = SyncOffText),
                    maxLines = 1,
                )
            }
            if (enabled && camera.state == CameraState.CameraOff) {
                Text(
                    text = context.getString(R.string.status_camera_off_short),
                    style = TextStyle(fontSize = 12.sp, color = StandbyText),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The header's state; the rows below show which cameras it applies to. */
private fun summary(context: Context, status: GeoShutterStatus): String = when {
    !status.enabled -> context.getString(R.string.status_off)
    status.sending.isNotEmpty() -> context.getString(R.string.status_sending_short)
    status.connecting.isNotEmpty() -> context.getString(R.string.status_connecting_short)
    status.cameraOff.isNotEmpty() -> context.getString(R.string.status_camera_off_short)
    status.locationSyncOff.isNotEmpty() -> context.getString(R.string.status_sync_off_short)
    else -> context.getString(R.string.status_waiting_short)
}

private const val MAX_CAMERAS = 4
