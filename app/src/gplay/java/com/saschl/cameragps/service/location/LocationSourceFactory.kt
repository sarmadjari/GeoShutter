package com.saschl.cameragps.service.location

import android.content.Context
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import com.sasch.cameragps.sharednew.ui.settings.LocationProvider
import com.saschl.cameragps.utils.PreferencesManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/** The gplay flavor lets the user pick between Play Services and the platform provider. */
const val LOCATION_PROVIDER_SELECTABLE = true

/**
 * gplay [LocationSource]: FusedLocationProviderClient (Play Services) with a
 * platform LocationManager fallback, selected via the location-provider
 * preference.
 */
fun createLocationSource(context: Context): LocationSource =
    SwitchingLocationSource(context.applicationContext)

private class SwitchingLocationSource(
    private val context: Context,
) : LocationSource {

    private val fused = FusedLocationSource(context)
    private val platform = PlatformLocationSource(context)

    // Only one source is started at a time, so merging is safe.
    override val locations: Flow<GeoLocation> = merge(fused.locations, platform.locations)

    private var active: LocationSource? = null
    private var intervalMs: Long? = null

    override fun setUpdateInterval(intervalMs: Long) {
        this.intervalMs = intervalMs
        active?.setUpdateInterval(intervalMs)
    }

    override fun start(): Boolean {
        active?.let { return true }
        // Re-read the provider preference on every (re)start, so a
        // LocationProviderCard change applies without process death.
        val source =
            if (PreferencesManager.getLocationProvider(context) == LocationProvider.PLAY_SERVICES) {
                fused
            } else {
                platform
            }
        intervalMs?.let(source::setUpdateInterval)
        val startedSuccessfully = source.start()
        if (startedSuccessfully) {
            active = source
        }
        return startedSuccessfully
    }

    override fun stop() {
        active?.stop()
        active = null
    }

    override fun hasPreciseAuthorization(): Boolean = true
}
