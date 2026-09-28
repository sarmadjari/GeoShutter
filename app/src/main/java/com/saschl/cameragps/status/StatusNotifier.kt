package com.saschl.cameragps.status

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.locationTransmissionNotificationId
import com.sasch.cameragps.sharednew.status.CameraStatus
import com.sasch.cameragps.sharednew.status.GeoShutterStatus
import com.saschl.cameragps.MainActivity
import com.saschl.cameragps.R
import com.saschl.cameragps.notification.NotificationsHelper

/** What the status notification says. */
internal data class StatusContent(val title: String, val text: String?, val sending: Boolean) {
    companion object {
        fun of(context: Context, status: GeoShutterStatus): StatusContent {
            val sending = status.sending
            val connecting = status.connecting
            fun names(cameras: List<CameraStatus>) = cameras.joinToString(", ") { it.name }
            return when {
                sending.size == 1 -> StatusContent(
                    context.getString(R.string.status_sending_one, sending.single().name),
                    sending.single().model,
                    sending = true,
                )

                sending.isNotEmpty() -> StatusContent(
                    context.getString(R.string.status_sending_many, sending.size),
                    names(sending),
                    sending = true,
                )

                connecting.size == 1 -> StatusContent(
                    context.getString(R.string.status_connecting_one, connecting.single().name),
                    connecting.single().model,
                    sending = false,
                )

                connecting.isNotEmpty() -> StatusContent(
                    context.getString(R.string.status_connecting_many, connecting.size),
                    names(connecting),
                    sending = false,
                )

                else -> StatusContent(
                    context.getString(R.string.status_waiting_title),
                    if (status.cameras.isEmpty()) {
                        context.getString(R.string.app_standby_content)
                    } else {
                        context.getString(R.string.status_waiting_for, names(status.cameras))
                    },
                    sending = false,
                )
            }
        }
    }
}

/**
 * Keeps the one status notification in line with [GeoShutterStatus]: the location
 * service's foreground notification while it runs, a quiet "waiting" notification
 * otherwise, and none while GeoShutter is off. It alerts when a camera starts receiving
 * the location, and when one stops if the disconnect channel is on; other changes
 * update it silently.
 */
internal class StatusNotifier(
    private val context: Context,
    private val isChannelEnabled: (String) -> Boolean = { channelId ->
        context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    },
    private val postNotification: (Int, Notification) -> Unit = { id, notification ->
        NotificationsHelper.showNotification(context, id, notification)
    },
    private val cancelNotification: (Int) -> Unit = { id ->
        context.getSystemService(NotificationManager::class.java).cancel(id)
    },
) {
    private var previousSending = 0
    private var lastContent: StatusContent? = null
    private var lastForeground = false

    /** [foreground]: the location service currently owns the notification. */
    fun publish(status: GeoShutterStatus, foreground: Boolean) {
        if (!status.enabled) {
            // Turned off: the service stops as well; leave without an alert.
            if (!foreground) cancelNotification(locationTransmissionNotificationId)
            previousSending = 0
            lastContent = null
            lastForeground = foreground
            return
        }
        val content = StatusContent.of(context, status)
        val sending = status.sending.size
        val increasing = sending > previousSending
        val decreasing = sending < previousSending
        if (!increasing && !decreasing && content == lastContent && foreground == lastForeground) {
            return
        }
        val disconnectAlert =
            decreasing && isChannelEnabled(NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL)
        val channelId = when {
            disconnectAlert -> NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL
            sending > 0 -> NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL
            else -> NotificationsHelper.NOTIFICATION_CHANNEL_ID
        }
        postNotification(
            locationTransmissionNotificationId,
            build(context, content, channelId, silent = !increasing && !disconnectAlert),
        )
        previousSending = sending
        lastContent = content
        lastForeground = foreground
    }

    companion object {
        /** A quiet notification for [status], e.g. to start the location service with. */
        fun quiet(context: Context, status: GeoShutterStatus): Notification {
            val content = StatusContent.of(context, status)
            val channelId = if (content.sending) {
                NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL
            } else {
                NotificationsHelper.NOTIFICATION_CHANNEL_ID
            }
            return build(context, content, channelId, silent = true)
        }

        fun build(
            context: Context,
            content: StatusContent,
            channelId: String,
            silent: Boolean,
        ): Notification = NotificationCompat.Builder(context, channelId)
            .setOngoing(true)
            .setSilent(silent)
            .setSmallIcon(if (content.sending) R.drawable.ic_gps_fixed else R.drawable.ic_gps_not_fixed)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0,
                context.getString(R.string.status_turn_off),
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, StatusActionReceiver::class.java)
                        .setAction(StatusActionReceiver.ACTION_TURN_OFF),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }
}
