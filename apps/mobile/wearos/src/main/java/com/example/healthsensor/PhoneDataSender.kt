/**
 * PhoneDataSender.kt — Wearable Data Layer를 통한 폰 전송 담당
 *
 * Wearable MessageClient를 사용하여 센서 배치 데이터를 폰으로 전송한다.
 *
 * 배치 메시지 형식 (워치 서비스가 약 200ms마다 채널별 배치로 전송):
 *   [count : Int  (4 bytes)]
 *   [timestamp_0 : Long (8 bytes)] [value_0 : Float (4 bytes)]
 *   [timestamp_1 : Long (8 bytes)] [value_1 : Float (4 bytes)]
 *   ...
 *   총 크기: 4 + N × 12 bytes
 *
 * 개별 메시지를 고주파(25–50Hz)로 전송하면 MessageClient 내부 큐가 포화되어
 * 한 센서가 채널을 독점하는 문제가 발생하므로 반드시 배치로 전송해야 한다.
 */
package com.example.healthsensor

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class PhoneDataSender(private val context: Context) {

    private val TAG = "PhoneDataSender"
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var phoneNodeId: String? = null

    /** 연결된 폰 노드 ID를 비동기로 탐색한다. 측정 시작 전에 한 번 호출해야 한다. */
    fun findPhoneNode() {
        scope.launch {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                phoneNodeId = nodes.firstOrNull()?.id
                Log.i(TAG, "폰 노드: $phoneNodeId")
            } catch (e: Exception) {
                Log.e(TAG, "폰 노드 검색 실패: ${e.message}")
            }
        }
    }

    /** 센서 샘플 목록을 배치 메시지로 직렬화하여 폰에 전송한다. */
    fun sendBatch(path: String, samples: List<Pair<Long, Float>>) {
        if (samples.isEmpty()) return
        val buf = ByteBuffer.allocate(4 + samples.size * 12)
        buf.putInt(samples.size)
        samples.forEach { (ts, v) ->
            buf.putLong(ts)
            buf.putFloat(v)
        }
        send(path, buf.array())
    }

    private fun send(path: String, payload: ByteArray) {
        scope.launch {
            runCatching {
                val nodeId = phoneNodeId ?: Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                    .firstOrNull()
                    ?.id
                    ?.also { phoneNodeId = it }
                    ?: return@runCatching
                Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, path, payload))
            }.onFailure {
                Log.e(TAG, "전송 실패 [$path]: ${it.message}")
            }
        }
    }
}
