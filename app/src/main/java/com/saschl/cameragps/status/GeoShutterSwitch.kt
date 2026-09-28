package com.saschl.cameragps.status

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.service.ServiceCommandRouter
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber

/** Turns GeoShutter on or off: the settings switch, the notification, tile and widget. */
object GeoShutterSwitch {
    fun setEnabled(context: Context, enabled: Boolean) {
        Timber.i("GeoShutter turned %s", if (enabled) "on" else "off")
        PreferencesManager.setAppEnabled(context, enabled)
        if (enabled) {
            connectSavedCameras(context)
        } else {
            // Ends every session; the status notification goes away with it.
            context.stopService(Intent(context, LocationSenderService::class.java))
        }
    }

    /**
     * Android reports a camera only when it starts advertising, so a camera that was
     * already on while GeoShutter was off would wait until it is switched off and on
     * again. Connect to the saved cameras right away instead.
     */
    fun connectSavedCameras(context: Context) {
        if (!LocationSenderService.hasLocationPermission(context)) {
            Timber.w("Location permission missing, not connecting the saved cameras")
            return
        }
        val intent = Intent(context, LocationSenderService::class.java)
            .setAction(ServiceCommandRouter.ACTION_CONNECT_SAVED)
        runCatching { context.startForegroundService(intent) }
            .onFailure { Timber.e(it, "Could not start the service to connect the saved cameras") }
    }
}

/** The status notification's "Turn off" button. */
class StatusActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TURN_OFF) GeoShutterSwitch.setEnabled(context, false)
    }

    companion object {
        const val ACTION_TURN_OFF = "com.saschl.cameragps.action.TURN_OFF"
    }
}
