package com.saschl.cameragps.service

import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteCommand

sealed interface ServiceCommand {
    /**
     * [direct]: the camera was just seen advertising and may stop soon (Fujifilm),
     * so connect with a direct attempt instead of waiting for the background one.
     */
    data class Connect(val address: String, val direct: Boolean = false) : ServiceCommand
    data class Shutdown(val address: String) : ServiceCommand
    data class TriggerRemoteShutter(val address: String) : ServiceCommand
    data class SendRemoteCommand(val address: String, val command: RemoteCommand) : ServiceCommand
    data class TriggerShutterSequence(val address: String) : ServiceCommand
    data class SetRemoteControlMonitoring(val address: String, val enabled: Boolean) :
        ServiceCommand
    data object ReconnectAlwaysOn : ServiceCommand
    data class Ignore(val reason: String) : ServiceCommand
}

