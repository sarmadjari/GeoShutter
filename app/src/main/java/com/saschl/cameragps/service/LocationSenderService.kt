package com.saschl.cameragps.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.locationTransmissionNotificationId
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import com.sasch.cameragps.sharednew.bluetooth.session.OrchestratorEvent
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.notification.NotificationsHelper
import com.saschl.cameragps.service.coordinator.ServiceShutdownCoordinator
import com.saschl.cameragps.service.transport.AndroidBleTransport
import com.saschl.cameragps.status.StatusNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * Thin Android lifecycle shell. Owns the foreground service, notifications,
 * sounds, intent routing and stopSelf(). All BLE/location/session logic lives
 * in the shared [CameraSessionOrchestrator]; the raw GATT/location plumbing in
 * [AndroidBleTransport] and the flavor-provided
 * [com.sasch.cameragps.sharednew.bluetooth.location.LocationSource].
 */
class LocationSenderService : LifecycleService() {

    private var hasForegroundSession = false
    private lateinit var eventSoundPlayer: EventSoundPlayer
    private lateinit var bluetoothStateReceiver: BluetoothStateBroadcastReceiver
    private val commandMutex = Mutex()
    private val commandRouter = ServiceCommandRouter()

    // The BLE/location graph is app-scoped (see [AppServices]); the service
    // borrows it for the duration of a foreground session.
    private val services get() = AppServices.from(this)
    private val orchestrator get() = services.orchestrator
    private val transport get() = services.transport
    private val bluetoothManager get() = services.bluetoothManager

    private val shutdownCoordinator by lazy {
        ServiceShutdownCoordinator(services.deviceDao, services.transport) { startId ->
            requestShutdown(startId)
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false

        private val runningState = MutableStateFlow(false)

        /** Whether the service exists; while it does, it owns the status notification. */
        val running: StateFlow<Boolean> = runningState.asStateFlow()

        /** How long [ServiceCommand.ConnectSaved] waits for a camera that is already on. */
        private val CONNECT_SAVED_TIMEOUT = 40.seconds

        /**
         * Check before every startForegroundService(): without a location grant,
         * startForeground() throws SecurityException, and stopping the service
         * then crashes with ForegroundServiceDidNotStartInTimeException.
         */
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
    }

    // ==================== Lifecycle ====================

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        runningState.value = true
    }

    @SuppressLint("MissingPermission")
    private fun startForegroundSession(): Boolean {
        eventSoundPlayer = EventSoundPlayer(this)
        NotificationsHelper.createNotificationChannel(this)

        if (!startAsForegroundService()) return false
        hasForegroundSession = true

        lifecycleScope.launch {
            orchestrator.events.collect { event -> handleEvent(event) }
        }
        // The status notification itself is kept up to date by StatusPublisher.
        return true
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        isRunning = false
        // Keep the status notification: StatusPublisher turns it into "waiting" (or
        // removes it when GeoShutter was turned off).
        if (hasForegroundSession) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
        runningState.value = false
        super.onDestroy()
        runCatching {
            if (::bluetoothStateReceiver.isInitialized) unregisterReceiver(bluetoothStateReceiver)
        }.onFailure { e ->
            Timber.e(e, "Failed to unregister Bluetooth state receiver")
        }
        if (hasForegroundSession) {
            orchestrator.shutdownAll()
            transport.disconnectAll()
        }
        if (::eventSoundPlayer.isInitialized) eventSoundPlayer.release()
        Timber.i("Destroyed service")
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val command = commandRouter.route(intent)
        // startService(shutdownIntent) can create a new instance. There is no
        // foreground session to shut down in that case, so don't promote it.
        if (command is ServiceCommand.Shutdown && !hasForegroundSession) {
            Timber.i("Ignoring shutdown request because no foreground session is running")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!hasForegroundSession && !startForegroundSession()) {
            return START_NOT_STICKY
        }

        if (!bluetoothManager.adapter.isEnabled) {
            Timber.w("Bluetooth is disabled, will shutdown service")
            requestShutdown(startId)
            return START_NOT_STICKY
        }

        lifecycleScope.launch {
            commandMutex.withLock {
                handleStartCommand(command, startId)
                Timber.i(
                    "processed start command $startId with intent action ${intent?.action} and address ${
                        intent?.getStringExtra(
                            "address"
                        )
                    }"
                )
            }
        }
        return START_REDELIVER_INTENT
    }

    @SuppressLint("MissingPermission")
    private fun handleEvent(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.DeviceConnected -> {
                eventSoundPlayer.play(TransmissionSoundEvent.CAMERA_CONNECTED)
            }

            is OrchestratorEvent.DeviceDisconnected -> {
                eventSoundPlayer.play(TransmissionSoundEvent.CAMERA_DISCONNECTED)
            }

            is OrchestratorEvent.HandshakeCompleted -> {
                // Setup is done — the periodic location writes don't need the
                // short connection interval requested at connect
                transport.relaxConnection(event.identifier)
            }

            is OrchestratorEvent.PairingFailed -> {
                // Pairing-failure UI state is tracked in AppServices
                Timber.e("Pairing failed for ${event.identifier}")
            }

            is OrchestratorEvent.FirstLocationAcquired -> {
                eventSoundPlayer.play(TransmissionSoundEvent.LOCATION_ACQUIRED)
            }

            is OrchestratorEvent.LocationUnavailable -> {
                eventSoundPlayer.play(TransmissionSoundEvent.LOCATION_INVALID)
            }
        }
    }

    // ==================== Command handling ====================

    @SuppressLint("MissingPermission")
    private suspend fun handleStartCommand(command: ServiceCommand, startId: Int) {
        when (command) {
            is ServiceCommand.Ignore -> {
                Timber.w(command.reason)
            }

            is ServiceCommand.ReconnectAlwaysOn -> {
                ensureBluetoothStateReceiver()
                shutdownCoordinator.handleNoAddress(startId)
            }

            is ServiceCommand.ConnectSaved -> {
                ensureBluetoothStateReceiver()
                connectSavedCameras(startId)
            }

            is ServiceCommand.Shutdown -> {
                shutdownCoordinator.handleShutdownRequest(command.address, startId)
            }

            is ServiceCommand.TriggerRemoteShutter -> {
                val success = orchestrator.triggerRemoteShutter(command.address)
                if (!success) {
                    Timber.w("Remote shutter request failed for ${command.address.uppercase()}")
                }
            }

            is ServiceCommand.SendRemoteCommand -> {
                val success = orchestrator.sendRemoteCommand(command.address, command.command)
                if (!success) {
                    Timber.w("Remote command ${command.command} failed for ${command.address.uppercase()}")
                }
            }

            is ServiceCommand.TriggerShutterSequence -> {
                val success = orchestrator.triggerShutterSequence(command.address)
                if (!success) {
                    Timber.w("Shutter sequence failed to start for ${command.address.uppercase()}")
                }
            }

            is ServiceCommand.SetRemoteControlMonitoring -> {
                orchestrator.setRemoteMonitoring(command.address, command.enabled)
            }

            is ServiceCommand.Connect -> {
                ensureBluetoothStateReceiver()
                if (!transport.hasConnection(command.address)) {
                    Timber.i("Service initialized")
                    orchestrator.onConnectRequested(command.address)
                    runCatching {
                        transport.connect(command.address, command.direct)
                    }.onSuccess { started ->
                        if (!started) {
                            // Not bonded (anymore) or Bluetooth permission missing:
                            // don't leave the session stuck in "connecting".
                            Timber.w("Could not connect to ${command.address}: not bonded or not permitted")
                            orchestrator.onConnectFailed(command.address)
                        }
                    }.onFailure {
                        Timber.e("Failed to connect to device, bluetooth is likely turned off")
                        orchestrator.onConnectFailed(command.address)
                    }
                } else if (command.direct && !transport.isConnected(command.address)) {
                    // The camera is advertising now: don't leave it to the slow background connection.
                    runCatching { transport.connect(command.address, direct = true) }
                        .onFailure { Timber.w(it, "Direct connection to ${command.address} failed") }
                }
            }
        }
    }

    /** See [ServiceCommand.ConnectSaved]. */
    @SuppressLint("MissingPermission")
    private suspend fun connectSavedCameras(startId: Int) {
        // The saved cameras are the companion associations (as in the camera list);
        // cameras switched off in the app are skipped.
        val associations = runCatching {
            getSystemService<CompanionDeviceManager>()
                ?.getAssociatedDevices(bluetoothManager.adapter)
                .orEmpty()
        }.onFailure { Timber.w(it, "Could not read the saved cameras") }
            .getOrDefault(emptyList())
        val addresses = associations.map { it.address.uppercase() }
            .filter { it != "N/A" && services.deviceDao.findDeviceEnabled(it) != false }
        Timber.i("Connecting to %d saved camera(s) that may already be on", addresses.size)
        for (address in addresses) {
            if (transport.isConnected(address)) continue
            if (!transport.hasConnection(address)) orchestrator.onConnectRequested(address)
            val started = runCatching { transport.connect(address, direct = true) }
                .onFailure { Timber.w(it, "Direct connection to $address failed") }
                .getOrDefault(false)
            if (!started) orchestrator.onConnectFailed(address)
        }
        // Cameras that are off don't answer: stop again unless one connected or one is
        // Always On. Android reports the others when they are switched on.
        lifecycleScope.launch {
            if (addresses.isNotEmpty()) delay(CONNECT_SAVED_TIMEOUT)
            shutdownCoordinator.stopIfIdle(startId)
        }
    }

    private fun ensureBluetoothStateReceiver() {
        if (!::bluetoothStateReceiver.isInitialized) {
            bluetoothStateReceiver = BluetoothStateBroadcastReceiver { enabled ->
                if (!enabled) {
                    Timber.w("Bluetooth turned off, will shutdown service")
                    requestShutdown()
                }
            }
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            ContextCompat.registerReceiver(
                this,
                bluetoothStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun startAsForegroundService(): Boolean {
        try {
            ServiceCompat.startForeground(
                this,
                locationTransmissionNotificationId,
                StatusNotifier.quiet(this, services.statusPublisher.status.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: SecurityException) {
            Timber.e("Failed to start foreground service due to missing permissions: ${e.message}")
            stopSelf()
            return false
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException / ForegroundServiceTypeException
            Timber.e(e, "Android did not allow the foreground service to start")
            stopSelf()
            return false
        }
        return true
    }

    private fun requestShutdown(startId: Int? = null) {
        if (startId != null) stopSelf(startId) else stopSelf()
    }
}
