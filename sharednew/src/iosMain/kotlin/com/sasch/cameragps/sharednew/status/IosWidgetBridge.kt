package com.sasch.cameragps.sharednew.status

import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.widget_no_cameras
import cameragps.sharednew.generated.resources.widget_status_connecting
import cameragps.sharednew.generated.resources.widget_status_off
import cameragps.sharednew.generated.resources.widget_status_sending
import cameragps.sharednew.generated.resources.widget_status_standby
import cameragps.sharednew.generated.resources.widget_status_sync_off
import cameragps.sharednew.generated.resources.widget_status_waiting
import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.BluetoothDeviceInfo
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSUserDefaults
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The iPhone widget and Control Center control live in the GeoShutterWidgets extension,
 * which can't run the app's code: the app writes GeoShutter's status as JSON into the app
 * group they share, and Swift installs the WidgetKit reloads here at launch.
 */
object IosWidgetBridge {
    const val APP_GROUP = "group.com.sarmadjari.geoshutter"
    const val STATUS_KEY = "status"

    private var reload: (() -> Unit)? = null

    /** Called by the Swift app at launch: reloads the widget timelines and the controls. */
    fun install(reload: () -> Unit) {
        this.reload = reload
    }

    /** Writes [json] and asks for a reload; false when the status hasn't changed. */
    internal fun publish(json: String): Boolean {
        val defaults = NSUserDefaults(suiteName = APP_GROUP)
        if (defaults.stringForKey(STATUS_KEY) == json) return false
        defaults.setObject(json, forKey = STATUS_KEY)
        reload()
        return true
    }

    internal fun reload() {
        reload?.invoke()
    }
}

/**
 * Keeps the widget's status up to date: the saved cameras of the camera list with their
 * state, and whether GeoShutter is on. iOS rations reloads while the app runs in the
 * background and may skip one that follows another closely, which left the widget showing
 * a state that lasted a second. So only settled states are written (after [DEBOUNCE_MS]
 * without change), an unchanged status isn't written again, and a change soon after
 * another gets one more reload [SETTLE_MS] later.
 */
internal class IosStatusPublisher(
    private val scope: CoroutineScope,
    /** The app's language setting: the texts follow it. */
    private val language: StateFlow<*>,
    private val appEnabled: StateFlow<Boolean>,
    private val devices: StateFlow<List<BluetoothDeviceInfo>>,
    private val sessions: StateFlow<Map<String, CameraSession>>,
    private val transmitting: StateFlow<Boolean>,
) {
    private val log = logging()
    private var texts: StatusTexts? = null
    private var lastWrite: TimeMark? = null
    private var settleReload: Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            // Again whenever the language changes in the app, which keeps running.
            language.collect {
                texts = runCatching { loadTexts() }
                    .onFailure { log.w(it, msg = { "Could not load the widget texts" }) }
                    .getOrNull()
                publishNow()
            }
        }
        scope.launch {
            combine(appEnabled, devices, sessions, transmitting, ::iosStatus)
                .debounce(DEBOUNCE_MS)
                .collect(::publish)
        }
    }

    /** Right away, e.g. before the control's action returns and the control reads it. */
    fun publishNow() {
        publish(iosStatus(appEnabled.value, devices.value, sessions.value, transmitting.value))
    }

    private fun publish(status: GeoShutterStatus) {
        val texts = texts ?: return
        if (!IosWidgetBridge.publish(statusSnapshot(status, texts).toJson())) return
        val previous = lastWrite
        lastWrite = TimeSource.Monotonic.markNow()
        if (previous != null && previous.elapsedNow() < SETTLE_MS.milliseconds) {
            settleReload?.cancel()
            settleReload = scope.launch {
                delay(SETTLE_MS)
                IosWidgetBridge.reload()
            }
        }
    }

    private suspend fun loadTexts() = StatusTexts(
        off = getString(Res.string.widget_status_off),
        sending = getString(Res.string.widget_status_sending),
        connecting = getString(Res.string.widget_status_connecting),
        standby = getString(Res.string.widget_status_standby),
        locationSyncOff = getString(Res.string.widget_status_sync_off),
        waiting = getString(Res.string.widget_status_waiting),
        noCameras = getString(Res.string.widget_no_cameras),
    )

    private companion object {
        const val DEBOUNCE_MS = 2_000L
        const val SETTLE_MS = 10_000L
    }
}

/**
 * The camera list's saved cameras, named as there, with their state. A camera still being
 * set up counts as not connected, as in the app's camera list: that takes seconds, which
 * the widget can't follow.
 */
internal fun iosStatus(
    enabled: Boolean,
    devices: List<BluetoothDeviceInfo>,
    sessions: Map<String, CameraSession>,
    transmitting: Boolean,
): GeoShutterStatus = GeoShutterStatus(
    enabled = enabled,
    cameras = devices.filter { it.isSaved }.map { device ->
        val id = device.identifier.uppercase()
        val state = cameraState(sessions[id], transmitting)
            .takeUnless { it == CameraState.Connecting } ?: CameraState.Away
        CameraStatus(id, device.name, device.model, state)
    },
)
