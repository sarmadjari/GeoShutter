package com.saschl.cameragps.status

import android.app.Notification
import android.app.NotificationManager
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.locationTransmissionNotificationId
import com.sasch.cameragps.sharednew.status.CameraState
import com.sasch.cameragps.sharednew.status.CameraStatus
import com.sasch.cameragps.sharednew.status.GeoShutterStatus
import com.saschl.cameragps.R
import com.saschl.cameragps.notification.NotificationsHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatusNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Two saved cameras, the first [count] of them receiving the location. */
    private fun sending(count: Int, firstName: String = "Camera 0") = GeoShutterStatus(
        enabled = true,
        cameras = List(2) { index ->
            CameraStatus(
                id = "ID$index",
                name = if (index == 0) firstName else "Camera $index",
                model = "Sony α1 II",
                state = if (index < count) CameraState.Sending else CameraState.Away,
            )
        },
    )

    private fun notifier(
        isChannelEnabled: (String) -> Boolean,
        posts: MutableList<Pair<Int, Notification>>,
        cancels: MutableList<Int> = mutableListOf(),
    ) = StatusNotifier(
        context,
        isChannelEnabled = isChannelEnabled,
        postNotification = { id, notification -> posts += id to notification },
        cancelNotification = { cancels += it },
    )

    @Test
    fun blockedDisconnectAlertsStillUpdateCountsAndRestoreWaiting() {
        val posts = mutableListOf<Pair<Int, Notification>>()
        val notifier = notifier({ it != NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL }, posts)

        for (count in listOf(0, 1, 2, 1, 0, 1)) notifier.publish(sending(count), foreground = true)

        assertEquals(List(6) { locationTransmissionNotificationId }, posts.map { it.first })
        val notifications = posts.map { it.second }
        assertEquals(
            listOf(
                NotificationsHelper.NOTIFICATION_CHANNEL_ID,
                NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL,
                NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL,
                NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL,
                NotificationsHelper.NOTIFICATION_CHANNEL_ID,
                NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL,
            ),
            notifications.map { it.channelId },
        )
        val waiting = context.getString(R.string.status_waiting_title)
        val one = context.getString(R.string.status_sending_one, "Camera 0")
        val two = context.resources.getQuantityString(R.plurals.status_sending_many, 2, 2)
        assertEquals(
            listOf(waiting, one, two, one, waiting, one),
            notifications.map { it.extras.getString(Notification.EXTRA_TITLE) },
        )
        // Count decreases are quiet; connecting another camera or reconnecting can alert again.
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, notifications[3].groupAlertBehavior)
        for (index in listOf(1, 2, 5)) {
            assertEquals(NotificationCompat.GROUP_ALERT_ALL, notifications[index].groupAlertBehavior)
            assertEquals(0, notifications[index].flags and Notification.FLAG_ONLY_ALERT_ONCE)
        }
    }

    @Test
    fun enabledDisconnectAlertsKeepTheirConfiguredChannel() {
        val posts = mutableListOf<Pair<Int, Notification>>()
        val notifier = notifier({ true }, posts)

        for (count in listOf(2, 1, 0)) notifier.publish(sending(count), foreground = true)

        val notifications = posts.map { it.second }
        assertEquals(NotificationsHelper.TRANSMISSION_NOTIFICATION_CHANNEL, notifications[0].channelId)
        for (notification in notifications.drop(1)) {
            assertEquals(NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL, notification.channelId)
            assertEquals(NotificationCompat.GROUP_ALERT_ALL, notification.groupAlertBehavior)
        }
        assertEquals(
            context.getString(R.string.status_waiting_for, "Camera 0, Camera 1"),
            notifications.last().extras.getString(Notification.EXTRA_TEXT),
        )
    }

    @Test
    fun aCameraWhoseLocationSyncIsSwitchedOffUpdatesQuietly() {
        val posts = mutableListOf<Pair<Int, Notification>>()
        val notifier = notifier({ true }, posts)
        val syncOff = GeoShutterStatus(
            enabled = true,
            cameras = listOf(
                CameraStatus("ID0", "X100VI-1A2B", "Fujifilm X100VI", CameraState.LocationSyncOff),
            ),
        )

        notifier.publish(sending(1, firstName = "X100VI-1A2B"), foreground = true)
        notifier.publish(syncOff, foreground = true)

        val switchedOff = posts.last().second
        assertEquals(NotificationsHelper.NOTIFICATION_CHANNEL_ID, switchedOff.channelId)
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, switchedOff.groupAlertBehavior)
        assertEquals(
            context.getString(R.string.status_sync_off_one, "X100VI-1A2B"),
            switchedOff.extras.getString(Notification.EXTRA_TITLE),
        )
    }

    @Test
    fun namesAndStateChangesWithoutANewCameraUpdateSilently() {
        val posts = mutableListOf<Pair<Int, Notification>>()
        val notifier = notifier({ true }, posts)

        notifier.publish(sending(1), foreground = true)
        notifier.publish(sending(1), foreground = true) // unchanged: not posted again
        notifier.publish(sending(1, firstName = "X100VI-1A2B"), foreground = true)

        assertEquals(2, posts.size)
        val renamed = posts.last().second
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, renamed.groupAlertBehavior)
        assertEquals(
            context.getString(R.string.status_sending_one, "X100VI-1A2B"),
            renamed.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals("Sony α1 II", renamed.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun turningOffRemovesTheWaitingNotificationButNotTheServiceOne() {
        val posts = mutableListOf<Pair<Int, Notification>>()
        val cancels = mutableListOf<Int>()
        val notifier = notifier({ true }, posts, cancels)
        val off = GeoShutterStatus(enabled = false)

        notifier.publish(off, foreground = true) // the service is still stopping
        assertEquals(emptyList<Int>(), cancels)
        notifier.publish(off, foreground = false)
        assertEquals(listOf(locationTransmissionNotificationId), cancels)
        assertEquals(emptyList<Pair<Int, Notification>>(), posts)
    }

    @Test
    fun transmissionUsesAnAlertingChannelWhileWaitingRemainsQuiet() {
        NotificationsHelper.createNotificationChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val waiting = StatusNotifier.quiet(context, sending(0))
        val posts = mutableListOf<Pair<Int, Notification>>()
        notifier({ true }, posts).publish(sending(1), foreground = true)
        val transmitting = posts.single().second
        assertNotEquals(waiting.channelId, transmitting.channelId)

        val waitingChannel = manager.getNotificationChannel(waiting.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, waitingChannel.importance)
        assertNull(waitingChannel.sound)
        val transmissionChannel = manager.getNotificationChannel(transmitting.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, transmissionChannel.importance)
        assertEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            transmissionChannel.sound,
        )
        // The alert replaces an existing waiting notification. ONLY_ALERT_ONCE
        // would silence that first transmission because it is an update.
        assertEquals(0, transmitting.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertNotEquals(0, transmitting.flags and Notification.FLAG_ONGOING_EVENT)
        // Both states offer "Turn off".
        assertEquals(
            context.getString(R.string.status_turn_off),
            transmitting.actions.single().title.toString(),
        )
    }
}
