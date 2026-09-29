package com.sasch.cameragps.sharednew.bluetooth.location

import kotlinx.coroutines.flow.Flow

/**
 * A platform-neutral location fix.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in meters; `< 0` means invalid (iOS convention). */
    val horizontalAccuracyMeters: Double,
    /** Epoch millis of the fix. */
    val timestampMillis: Long,
    /**
     * Altitude in metres when known: above mean sea level where the platform
     * provides it, otherwise above the WGS84 ellipsoid.
     */
    val altitudeMeters: Double? = null,
)

/** Location update interval while every camera is switched off in standby; see [LocationSource.setSlowUpdates]. */
const val STANDBY_LOCATION_UPDATE_INTERVAL_MS = 60_000L

/**
 * Platform location provider. Android wraps FusedLocationProviderClient with a
 * LocationManager fallback; iOS wraps CLLocationManager.
 */
interface LocationSource {

    /** Hot stream of fixes while started. Emissions may come from any thread/looper. */
    val locations: Flow<GeoLocation>

    /**
     * Start delivering fixes. Idempotent. Android also fires its one-shot
     * `getCurrentLocation`/last-known seed here.
     * Returns `false` if updates could not be started (e.g. no provider enabled).
     */
    fun start(): Boolean

    fun stop()

    /**
     * About one fix a minute instead of one every few seconds, while every camera that
     * takes locations is switched off in standby (Fujifilm). Applies while started and
     * to the next start. Sources that can't slow down ignore it.
     */
    fun setSlowUpdates(slow: Boolean) = Unit

    /** iOS: `CLAccuracyAuthorizationFullAccuracy`. Android: always `true`. */
    fun hasPreciseAuthorization(): Boolean
}
