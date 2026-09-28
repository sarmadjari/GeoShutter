package com.saschl.cameragps.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.saschl.cameragps.R

internal object NotificationsHelper {

    const val NOTIFICATION_CHANNEL_ID = "general_notification_channel"

    // The old low-importance channel remains the quiet foreground waiting state.
    // Android cannot raise an existing channel's importance, so transmission
    // alerts need their own channel, including for existing installations.
    const val TRANSMISSION_NOTIFICATION_CHANNEL = "transmission_notification_channel"
    const val DISCONNECT_NOTIFICATION_CHANNEL = "disconnect_notification_channel"


    fun createNotificationChannel(context: Context) {
        val notificationManager =
            context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.app_standby_title),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)
        channel.enableVibration(false)

        val transmissionChannel = NotificationChannel(
            TRANSMISSION_NOTIFICATION_CHANNEL,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        transmissionChannel.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )

        val disconnectChannel = NotificationChannel(
            DISCONNECT_NOTIFICATION_CHANNEL,
            context.getString(R.string.disconnect_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        disconnectChannel.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(transmissionChannel)
        notificationManager.createNotificationChannel(disconnectChannel)
    }

    fun showNotification(context: Context, notificationId: Int, notification: Notification) {
        val notificationManager =
            context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(notificationId, notification)
    }
}
