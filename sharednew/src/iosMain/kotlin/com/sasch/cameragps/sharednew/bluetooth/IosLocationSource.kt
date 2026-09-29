package com.sasch.cameragps.sharednew.bluetooth

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyNearestTenMeters
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject

/**
 * iOS [LocationSource] over CLLocationManager. Owns the manager configuration,
 * the delegate and the WhenInUse→Always authorization escalation; all gating
 * and periodic-send logic lives in the shared LocationTransmissionManager.
 *
 * Main-thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosLocationSource : LocationSource {

    private val log = logging()

    /** Set by the controller to re-evaluate tracking when authorization changes. */
    var onAuthorizationChanged: (() -> Unit)? = null

    private val locationChannel = Channel<GeoLocation>(Channel.UNLIMITED)
    override val locations: Flow<GeoLocation> = locationChannel.receiveAsFlow()

    private var started = false

    /** The distance filter for the current interval; see [setUpdateInterval]. */
    private var distanceFilterMeters = DISTANCE_FILTER_METERS

    private val locationDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return

            // avoid delivering stale fixes
            val ageSeconds = -location.timestamp.timeIntervalSinceNow
            if (ageSeconds > MAX_FIX_AGE_SECONDS) {
                log.w { "Ignoring stale location fix (${ageSeconds.toInt()}s old)" }
                // Deliver everything until a fresh fix arrives.
                manager.distanceFilter = kCLDistanceFilterNone
                return
            }
            if (manager.distanceFilter != distanceFilterMeters) {
                manager.distanceFilter = distanceFilterMeters
            }
            log.d { "Received new location" }
            val lat = location.coordinate.useContents { latitude }
            val lng = location.coordinate.useContents { longitude }
            locationChannel.trySend(
                GeoLocation(
                    latitude = lat,
                    longitude = lng,
                    horizontalAccuracyMeters = location.horizontalAccuracy,
                    timestampMillis = (location.timestamp.timeIntervalSince1970 * 1000.0).toLong(),
                    // Above mean sea level; a negative vertical accuracy marks it invalid.
                    altitudeMeters = location.altitude.takeIf { location.verticalAccuracy >= 0 },
                )
            )
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            log.e { "Location error" }
            log.d {
                "Location error: domain=${didFailWithError.domain} code=${didFailWithError.code} " +
                        "description=${didFailWithError.localizedDescription}, " +
                        "authorization=${manager.authorizationStatus()}, started=$started"
            }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (manager.authorizationStatus() == kCLAuthorizationStatusAuthorizedWhenInUse) {
                manager.requestAlwaysAuthorization()
            }
            onAuthorizationChanged?.invoke()
        }
    }

    private val locationManager = CLLocationManager().apply {
        delegate = locationDelegate
        desiredAccuracy = kCLLocationAccuracyBest
        distanceFilter = DISTANCE_FILTER_METERS
        pausesLocationUpdatesAutomatically = false
        allowsBackgroundLocationUpdates = true
    }

    override fun start(): Boolean {
        if (!started) {
            // notDetermined is the only status where a request shows UI: denied and
            // restricted are silent no-ops, and the WhenInUse→Always escalation is
            // handled by locationManagerDidChangeAuthorization, which Core Location
            // also fires once when the manager is created.
            if (locationManager.authorizationStatus() == kCLAuthorizationStatusNotDetermined) {
                locationManager.requestWhenInUseAuthorization()
            }
            locationManager.startUpdatingLocation()
            started = true
        }
        return true
    }

    override fun stop() {
        if (started) {
            locationManager.stopUpdatingLocation()
            started = false
        }
    }

    /**
     * Core Location has no update interval. When every camera needs a location only
     * every minute or less often (a Fujifilm camera in standby, or a long sync
     * interval), ask for ten-metre accuracy and fixes every ten metres, which lets iOS
     * save power; otherwise the best accuracy and fixes every two metres.
     */
    override fun setUpdateInterval(intervalMs: Long) {
        val relaxed = intervalMs >= RELAXED_INTERVAL_MS
        val accuracy = if (relaxed) kCLLocationAccuracyNearestTenMeters else kCLLocationAccuracyBest
        val filter = if (relaxed) RELAXED_DISTANCE_FILTER_METERS else DISTANCE_FILTER_METERS
        if (locationManager.desiredAccuracy == accuracy && distanceFilterMeters == filter) return
        log.i { "Location every ${intervalMs / 1000} s: ${if (relaxed) "ten-metre" else "best"} accuracy" }
        distanceFilterMeters = filter
        locationManager.desiredAccuracy = accuracy
        locationManager.distanceFilter = filter
    }

    override fun hasPreciseAuthorization(): Boolean =
        locationManager.accuracyAuthorization() ==
                CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy

    /**
     * True when authorization is stuck below Always and only the Settings app can
     * fix it: iOS shows the WhenInUse→Always upgrade prompt at most once, and a
     * denied/restricted status never prompts again at all.
     */
    fun needsAlwaysAuthorizationFromSettings(): Boolean =
        when (locationManager.authorizationStatus()) {
            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted,
            -> true

            else -> false
        }

    private companion object {
        /** Matches AndroidLocationSource's staleness gate for delivered fixes. */
        const val MAX_FIX_AGE_SECONDS = 30.0
        const val DISTANCE_FILTER_METERS = 2.0

        /** From this interval on, fixes may be less precise and less frequent. */
        const val RELAXED_INTERVAL_MS = 60_000L
        const val RELAXED_DISTANCE_FILTER_METERS = 10.0
    }
}
