package com.saschl.cameragps.status

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sasch.cameragps.sharednew.status.GeoShutterStatus
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.R
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber

/**
 * Quick Settings tile: shows whether GeoShutter is on and what it is doing
 * ("Waiting", a camera's name, "2 sending"); a tap turns GeoShutter on or off.
 */
class StatusTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val enabled = !PreferencesManager.isAppEnabled(this)
        GeoShutterSwitch.setEnabled(this, enabled)
        render(enabledNow = enabled)
    }

    private fun render(enabledNow: Boolean? = null) {
        val tile = qsTile ?: return
        val status = AppServices.from(this).statusPublisher.status.value
        val enabled = enabledNow ?: status.enabled
        val sending = enabled && status.sending.isNotEmpty()
        tile.label = getString(R.string.status_app_name)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (sending) R.drawable.ic_gps_fixed else R.drawable.ic_gps_not_fixed,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle(status, enabled)
        }
        tile.updateTile()
    }

    private fun subtitle(status: GeoShutterStatus, enabled: Boolean): String = when {
        !enabled -> getString(R.string.status_off)
        status.sending.size == 1 -> status.sending.single().name
        status.sending.size > 1 -> getString(R.string.status_sending_count, status.sending.size)
        status.connecting.isNotEmpty() -> getString(R.string.status_connecting_short)
        else -> getString(R.string.status_waiting_short)
    }

    companion object {
        /** Ask the system to re-render the tile if the user added it. */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, StatusTileService::class.java))
            }.onFailure { Timber.d(it, "Could not refresh the Quick Settings tile") }
        }
    }
}
