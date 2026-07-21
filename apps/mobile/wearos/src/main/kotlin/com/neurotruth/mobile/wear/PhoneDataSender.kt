package com.neurotruth.mobile.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Sends batched sensor samples to the phone over the Wearable Data Layer.
 *
 * The wire format is fixed by PRD §5.5:
 *
 * ```text
 * [count : Int32] then count × ( [timestampMs : Int64] [value : Float32] )
 * total bytes = 4 + count * 12
 * ```
 *
 * One message per sample is not an option: at 25 Hz across nine channels it saturates the
 * `MessageClient` queue and lets a single sensor starve the rest. The watch therefore sends one
 * batch per channel per flush cycle.
 *
 * `ByteBuffer` is big-endian by default, and the phone decoder reads it the same way.
 */
class PhoneDataSender(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var phoneNodeId: String? = null

    /** Resolves the paired phone node. Call once before measurement starts. */
    fun findPhoneNode() {
        scope.launch {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                phoneNodeId = nodes.firstOrNull()?.id
            }.onFailure { Log.e(TAG, "폰 노드 검색 실패: ${it.message}") }
        }
    }

    fun sendBatch(path: String, samples: List<Pair<Long, Float>>) {
        if (samples.isEmpty()) return
        val buffer = ByteBuffer.allocate(HEADER_BYTES + samples.size * SAMPLE_BYTES)
        buffer.putInt(samples.size)
        for ((timestampMs, value) in samples) {
            buffer.putLong(timestampMs)
            buffer.putFloat(value)
        }
        send(path, buffer.array())
    }

    fun close() {
        scope.cancel()
    }

    private fun send(path: String, payload: ByteArray) {
        scope.launch {
            runCatching {
                val nodeId = phoneNodeId
                    ?: Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                        .firstOrNull()
                        ?.id
                        ?.also { phoneNodeId = it }
                    ?: return@runCatching
                Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, path, payload))
            }.onFailure {
                // A dropped batch is acceptable: the watch buffers only within a flush cycle and
                // gapped data is preferable to stale data presented as current.
                phoneNodeId = null
                Log.e(TAG, "전송 실패 [$path]: ${it.message}")
            }
        }
    }

    private companion object {
        const val TAG = "PhoneDataSender"
        const val HEADER_BYTES = 4
        const val SAMPLE_BYTES = 12
    }
}

/** The nine Data Layer channel paths of PRD §5.5. */
object WatchSensorPaths {
    const val HR = "/sensor/hr"
    const val PPG = "/sensor/ppg"
    const val PPG_IR = "/sensor/ppg_ir"
    const val PPG_RED = "/sensor/ppg_red"
    const val EDA = "/sensor/eda"
    const val ACCEL_X = "/sensor/accel_x"
    const val ACCEL_Y = "/sensor/accel_y"
    const val ACCEL_Z = "/sensor/accel_z"
    const val SKIN_TEMP = "/sensor/skin_temp"
}
