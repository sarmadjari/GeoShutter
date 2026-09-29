package com.saschl.cameragps.service.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import com.sasch.cameragps.sharednew.bluetooth.location.STANDBY_LOCATION_UPDATE_INTERVAL_MS
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/**
 * gplay-only [LocationSource] backed by the Play Services
 * FusedLocationProviderClient. [PlatformLocationSource] remains the fallback,
 * selected via the location-provider preference in [createLocationSource].
 */
class FusedLocationSource(
    private val context: Context,
) : LocationSource {

    private val locationChannel = Channel<GeoLocation>(Channel.UNLIMITED)
    override val locations: Flow<GeoLocation> = locationChannel.receiveAsFlow()

    private var started = false
    private var slow = false
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (started) return true
        if (!hasAnyLocationProviderEnabled(context)) {
            Timber.e("No location providers enabled, cannot start location updates")
            return false
        }
        initializeIfNeeded()

        return try {
            started = true
            startPlayServicesLocationUpdates()
            true
        } catch (e: Exception) {
            started = false
            Timber.e(e, "Failed to start location updates")
            false
        }
    }

    override fun stop() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        started = false
    }

    override fun setSlowUpdates(slow: Boolean) {
        if (this.slow == slow) return
        this.slow = slow
        if (!started) return
        val client = fusedLocationClient ?: return
        val callback = locationCallback ?: return
        client.removeLocationUpdates(callback)
        requestLocationUpdates(client)
    }

    override fun hasPreciseAuthorization(): Boolean = true

    // --- private helpers ---

    private fun initializeIfNeeded() {
        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        }

        if (locationCallback == null) {
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(fetchedLocation: LocationResult) {
                    super.onLocationResult(fetchedLocation)
                    Timber.d("Got a new location")
                    fetchedLocation.lastLocation?.let { emitLocation(it) }
                }
            }
        }
    }

    private fun emitLocation(location: Location) {
        locationChannel.trySend(
            GeoLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                horizontalAccuracyMeters = location.accuracy.toDouble(),
                timestampMillis = location.time,
                altitudeMeters = location.altitudeMetersOrNull(),
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun startPlayServicesLocationUpdates() {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)
        if (resultCode != ConnectionResult.SUCCESS) {
            Timber.w("Google Play Services unavailable (code: $resultCode). Check location provider setting.")
            return
        }

        val fusedClient = fusedLocationClient
        if (fusedClient == null) {
            Timber.e("FusedLocationProviderClient not initialized")
            return
        }

        // One-shot seed so the camera gets a fix without waiting for the first update
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    if (isLocationTooOld(location)) {
                        Timber.w("Ignoring stale initial location from Play Services")
                    } else {
                        emitLocation(location)
                    }
                }
            }.addOnFailureListener { e ->
                Timber.e(e, "Failed to get initial location from Play Services")
            }

        requestLocationUpdates(fusedClient)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(fusedClient: FusedLocationProviderClient) {
        val intervalMs = if (slow) STANDBY_LOCATION_UPDATE_INTERVAL_MS else LOCATION_UPDATE_INTERVAL_MS
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setWaitForAccurateLocation(true)
            .setMinUpdateDistanceMeters(2f)
            .build()

        val callback = locationCallback ?: return
        val locationSettings = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)

        LocationServices.getSettingsClient(context).checkLocationSettings(locationSettings.build())
            .addOnSuccessListener {
                // Stopped while the settings check ran: don't start updates after all.
                if (!started) return@addOnSuccessListener
                Timber.d("Location Settings are satisfied, requesting a location every ${intervalMs / 1000} s")
                fusedClient.requestLocationUpdates(
                    locationRequest,
                    callback,
                    Looper.getMainLooper()
                )
            }.addOnFailureListener { exception ->
                Timber.e(
                    exception,
                    "Location settings are not satisfied, cannot start location updates"
                )
            }
    }
}
