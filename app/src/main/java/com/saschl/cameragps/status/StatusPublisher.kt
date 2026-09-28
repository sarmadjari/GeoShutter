package com.saschl.cameragps.status

import android.companion.CompanionDeviceManager
import android.content.Context
import androidx.core.content.getSystemService
import com.sasch.cameragps.sharednew.status.GeoShutterStatus
import com.sasch.cameragps.sharednew.status.SavedCamera
import com.sasch.cameragps.sharednew.status.geoShutterStatus
import com.sasch.cameragps.sharednew.ui.devicelist.CameraBrand
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.notification.NotificationsHelper
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.service.getAssociatedDevices
import com.saschl.cameragps.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * The single source of GeoShutter's status outside the app: computes [status] from
 * the Enable App setting, the saved cameras and the live sessions, and keeps the
 * status notification, the Quick Settings tile and the home-screen widget in line
 * with it. App-scoped; started once per process.
 */
class StatusPublisher(private val context: Context, private val services: AppServices) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifier = StatusNotifier(context)

    private val _status =
        MutableStateFlow(GeoShutterStatus(enabled = PreferencesManager.isAppEnabled(context)))
    val status: StateFlow<GeoShutterStatus> = _status

    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        NotificationsHelper.createNotificationChannel(context)

        val devices = services.deviceDao.observeAllDevices()
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)
        // The saved cameras are the companion associations, which change together with
        // the devices table (adding or removing a camera).
        val savedCameras = devices.map { savedCameras() }

        scope.launch {
            combine(
                PreferencesManager.appEnabledFlow(context),
                savedCameras,
                devices,
                services.orchestrator.sessions,
                services.orchestrator.locationManager.isActive,
                ::geoShutterStatus,
            ).distinctUntilChanged().collect { _status.value = it }
        }
        scope.launch {
            combine(status, LocationSenderService.running) { status, running -> status to running }
                .collect { (status, running) ->
                    runCatching { notifier.publish(status, foreground = running) }
                        .onFailure { Timber.w(it, "Could not update the status notification") }
                }
        }
        scope.launch {
            status.collect { StatusTileService.refresh(context) }
        }
        scope.launch {
            status.drop(1).debounce(WIDGET_UPDATE_DELAY).collect {
                runCatching { StatusWidget.refresh(context) }
                    .onFailure { Timber.w(it, "Could not update the status widget") }
            }
        }
    }

    private fun savedCameras(): List<SavedCamera> {
        val manager = context.getSystemService<CompanionDeviceManager>() ?: return emptyList()
        val adapter = services.bluetoothManager.adapter ?: return emptyList()
        val associations = runCatching { manager.getAssociatedDevices(adapter) }
            .onFailure { Timber.w(it, "Could not read the saved cameras") }
            .getOrDefault(emptyList())
        return associations.map {
            SavedCamera(
                id = it.address,
                pairingName = it.name,
                brand = when {
                    it.isFujifilm -> CameraBrand.Fujifilm
                    it.isSony -> CameraBrand.Sony
                    else -> null
                },
            )
        }
    }

    private companion object {
        val WIDGET_UPDATE_DELAY = 500.milliseconds
    }
}
