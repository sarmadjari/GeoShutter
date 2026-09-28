package com.saschl.cameragps.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber

class RebootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, LocationSenderService::class.java)

        Timber.i(
            "RebootReceiver received intent: ${intent.action} with preference ${
                PreferencesManager.getAutoStartAfterBootEnabled(context)
            }"
        )
        if (!PreferencesManager.isAppEnabled(context)) {
            Timber.i("App is disabled, not starting LocationSenderService")
            return
        }
        if (!LocationSenderService.hasLocationPermission(context)) {
            Timber.e("Location permission missing, not starting LocationSenderService")
            return
        }

        val shouldStart = when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            Intent.ACTION_BOOT_COMPLETED -> PreferencesManager.getAutoStartAfterBootEnabled(context)
            else -> false
        }
        if (!shouldStart) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // The update ended every connection, and Android does not report cameras
            // that are still on again: connect to them.
            serviceIntent.action = ServiceCommandRouter.ACTION_CONNECT_SAVED
        }

        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: never crash at boot or update.
            Timber.e(e, "Could not start LocationSenderService after ${intent.action}")
        }
    }
}
