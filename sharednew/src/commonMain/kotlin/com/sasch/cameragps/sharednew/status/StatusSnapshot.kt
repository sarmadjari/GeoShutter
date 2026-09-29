package com.sasch.cameragps.sharednew.status

/**
 * What the iPhone widget and Control Center control show, written as JSON into the
 * app group they share with the app (their extension can't run the app's code). Texts
 * are localized by the app; the extension only lays them out.
 */
data class StatusSnapshot(
    val enabled: Boolean,
    /** A camera receives the location: the icon shows the pin in the frame. */
    val sending: Boolean,
    /** GeoShutter's state in a few words, e.g. "Sending location" ([StatusHeadline]). */
    val headline: String,
    /** Shown instead of the cameras when none is saved. */
    val emptyText: String,
    val cameras: List<Camera>,
) {
    data class Camera(
        val id: String,
        val name: String,
        /** Brand and model, e.g. "Fujifilm X100VI"; null when unknown or the same as [name]. */
        val model: String?,
        /** The dot's state: sending, connecting, syncOff, standby, away, or off while GeoShutter is off. */
        val state: String,
        /** An extra line, e.g. "Standby"; null when the state needs none. */
        val note: String?,
    )

    fun toJson(): String = buildString {
        append("{\"enabled\":").append(enabled)
        append(",\"sending\":").append(sending)
        append(",\"headline\":").appendJson(headline)
        append(",\"emptyText\":").appendJson(emptyText)
        append(",\"cameras\":[")
        cameras.forEachIndexed { index, camera ->
            if (index > 0) append(',')
            append("{\"id\":").appendJson(camera.id)
            append(",\"name\":").appendJson(camera.name)
            append(",\"model\":").appendJson(camera.model)
            append(",\"state\":").appendJson(camera.state)
            append(",\"note\":").appendJson(camera.note)
            append('}')
        }
        append("]}")
    }
}

/** The localized texts a [StatusSnapshot] needs. */
data class StatusTexts(
    val off: String,
    val sending: String,
    val connecting: String,
    val standby: String,
    val locationSyncOff: String,
    val waiting: String,
    val noCameras: String,
)

/** The snapshot of [status], with the Android widget's texts and colors (dot states). */
fun statusSnapshot(status: GeoShutterStatus, texts: StatusTexts): StatusSnapshot = StatusSnapshot(
    enabled = status.enabled,
    sending = status.enabled && status.sending.isNotEmpty(),
    headline = when (status.headline()) {
        StatusHeadline.Off -> texts.off
        StatusHeadline.Sending -> texts.sending
        StatusHeadline.Connecting -> texts.connecting
        StatusHeadline.Standby -> texts.standby
        StatusHeadline.LocationSyncOff -> texts.locationSyncOff
        StatusHeadline.Waiting -> texts.waiting
    },
    emptyText = texts.noCameras,
    cameras = status.cameras.map { camera ->
        StatusSnapshot.Camera(
            id = camera.id,
            name = camera.name,
            model = camera.model,
            state = if (!status.enabled) "off" else when (camera.state) {
                CameraState.Sending -> "sending"
                CameraState.Connecting -> "connecting"
                CameraState.LocationSyncOff -> "syncOff"
                CameraState.Standby -> "standby"
                CameraState.Away -> "away"
            },
            note = when {
                !status.enabled -> null
                camera.state == CameraState.LocationSyncOff -> texts.locationSyncOff
                camera.state == CameraState.Standby -> texts.standby
                else -> null
            },
        )
    },
)

private fun StringBuilder.appendJson(value: String?): StringBuilder {
    if (value == null) return append("null")
    append('"')
    value.forEach { char ->
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> append(char)
        }
    }
    return append('"')
}
