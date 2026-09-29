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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSUserDefaults

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

    /** Writes [json] and asks for a reload, unless the status hasn't changed. */
    internal fun publish(json: String) {
        val defaults = NSUserDefaults(suiteName = APP_GROUP)
        if (defaults.stringForKey(STATUS_KEY) == json) return
        defaults.setObject(json, forKey = STATUS_KEY)
        reload?.invoke()
    }
}

/**
 * Keeps the widget's status up to date: the saved cameras of the camera list with their
 * state, and whether GeoShutter is on. While the app is connected to a camera, iOS reloads
 * the widget at most every 5 minutes, also while the app is open (the system log says
 * "Throttling bluetooth refresh request"). It keeps the request and reloads at the next
 * 5-minute mark, and the widget then reads the latest status. So a written status has to
 * hold for minutes: see [settled]. An unchanged status isn't written again.
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
    /** The status written last. */
    private var published: GeoShutterStatus? = null

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
                .settled { published }
                .collect(::publish)
        }
    }

    /** Right away, e.g. before the control's action returns and the control reads it. */
    fun publishNow() {
        publish(iosStatus(appEnabled.value, devices.value, sessions.value, transmitting.value))
    }

    private fun publish(status: GeoShutterStatus) {
        val texts = texts ?: return
        published = status
        IosWidgetBridge.publish(statusSnapshot(status, texts).toJson())
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
}

/** Changes are written once they have lasted this long. */
internal const val STATUS_DEBOUNCE_MS = 2_000L

/**
 * A camera that drops keeps its state this long on the widget: a Sony camera's power save
 * ends the connection about every minute and the phone reconnects within about 12 s.
 */
internal const val CAMERA_DROP_GRACE_MS = 30_000L

/**
 * The statuses worth writing: settled ones only, and a camera that drops for a moment
 * doesn't show as away ([publishDelayMs]). [published] is the status written last.
 */
@OptIn(FlowPreview::class)
internal fun Flow<GeoShutterStatus>.settled(published: () -> GeoShutterStatus?): Flow<GeoShutterStatus> =
    distinctUntilChanged().debounce { publishDelayMs(it, published()) }

/**
 * How long [status] must last before it is written: [CAMERA_DROP_GRACE_MS] when a camera
 * that was connected in the [published] status no longer is, so a quick reconnect writes
 * nothing; [STATUS_DEBOUNCE_MS] otherwise.
 */
internal fun publishDelayMs(status: GeoShutterStatus, published: GeoShutterStatus?): Long {
    if (published == null || published.enabled != status.enabled) return STATUS_DEBOUNCE_MS
    val connected = published.cameras.filter { it.state != CameraState.Away }.map { it.id }.toSet()
    val dropped = status.cameras.any { it.id in connected && it.state == CameraState.Away }
    return if (dropped) CAMERA_DROP_GRACE_MS else STATUS_DEBOUNCE_MS
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
