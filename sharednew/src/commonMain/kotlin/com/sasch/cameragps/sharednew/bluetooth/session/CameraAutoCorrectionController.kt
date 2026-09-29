package com.sasch.cameragps.sharednew.bluetooth.session

import com.sasch.cameragps.sharednew.bluetooth.BleSessionPhase
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperation
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Optional camera settings, independent of the sequence-sensitive GPS handshake. */
internal class CameraAutoCorrectionController(
    private val port: QueuedBleGattPort,
    private val registry: CameraSessionRegistry,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableMapOf<Pair<String, CameraAutoCorrectionSetting>, Job>()

    fun refresh(identifier: String) {
        val id = identifier.uppercase()
        if (!isReady(id)) return
        val protocol = registry.get(id)?.protocol ?: return
        for (setting in CameraAutoCorrectionSetting.forProtocol(protocol)) {
            val old = registry.get(id)?.autoCorrectionSetting(setting) ?: continue
            if (old.pending) continue
            if (!port.supportsWriteWithResponse(id, setting.characteristicUuid)) {
                port.setAutoCorrectionState(id, setting, CameraSettingState(supported = false))
                continue
            }
            port.setAutoCorrectionState(
                id,
                setting,
                old.copy(pending = true, failed = false)
            )
            jobs[id to setting] = scope.launch {
                val result = port.execute(
                    id, BleOperation.Read(setting.characteristicUuid, setting.serviceUuid),
                )
                coroutineContext.ensureActive()
                val value = setting.decode((result as? BleOperationResult.Success)?.value)
                port.setAutoCorrectionState(
                    id, setting, CameraSettingState(
                        supported = if (value != null) true else old.supported,
                        enabled = value ?: old.enabled,
                        failed = value == null,
                    )
                )
            }
        }
    }

    fun set(identifier: String, setting: CameraAutoCorrectionSetting, enabled: Boolean) {
        val id = identifier.uppercase()
        if (!isReady(id)) return
        val old = registry.get(id)?.autoCorrectionSetting(setting) ?: return
        if (old.supported != true || old.enabled == null || old.pending || old.enabled == enabled) return
        port.setAutoCorrectionState(id, setting, old.copy(pending = true, failed = false))
        jobs[id to setting] = scope.launch {
            val result = port.execute(
                id, BleOperation.Write(
                    setting.characteristicUuid, setting.encode(enabled), setting.serviceUuid,
                )
            )
            coroutineContext.ensureActive()
            val success = result is BleOperationResult.Success
            port.setAutoCorrectionState(
                id, setting, old.copy(
                    enabled = if (success) enabled else old.enabled,
                    pending = false, failed = !success,
                )
            )
        }
    }

    /** The camera reported a new value itself (changed in its menu, or after a write). */
    fun onNotified(identifier: String, setting: CameraAutoCorrectionSetting, value: ByteArray) {
        val id = identifier.uppercase()
        val enabled = setting.decode(value) ?: return
        val old = registry.get(id)?.autoCorrectionSetting(setting) ?: return
        // A write in flight reports its own result.
        if (old.pending) return
        port.setAutoCorrectionState(
            id,
            setting,
            CameraSettingState(
                supported = port.supportsWriteWithResponse(id, setting.characteristicUuid),
                enabled = enabled,
            ),
        )
    }

    fun clear(identifier: String) {
        val keys = jobs.keys.filter { it.first == identifier.uppercase() }
        keys.forEach { jobs.remove(it)?.cancel() }
    }

    fun clearAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun isReady(id: String) =
        port.isConnected(id) && registry.get(id)?.phase == BleSessionPhase.Transmitting
}
