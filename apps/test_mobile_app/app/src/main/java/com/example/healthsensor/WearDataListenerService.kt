/**
 * WearDataListenerService.kt — 워치에서 오는 센서 데이터 수신 서비스 (폰 측)
 *
 * WearableListenerService를 상속하여 워치가 전송한 MessageEvent를 수신하고
 * SensorRepository의 Flow로 전달한다.
 *
 * 수신 메시지 형식 (배치):
 *   [count : Int  (4 bytes)]
 *   [timestamp_N : Long (8 bytes)] [value_N : Float (4 bytes)]  × count
 *   총 크기: 4 + N × 12 bytes
 *
 * 처리 경로 (15채널):
 *   /sensor/hr         — 심박수 BPM
 *   /sensor/ppg        — PPG Green Raw
 *   /sensor/ppg_ir     — PPG IR Raw
 *   /sensor/ppg_red    — PPG Red Raw
 *   /sensor/eda        — 피부 전기 전도도 (μS)
 *   /sensor/accel_x    — 가속도 X축
 *   /sensor/accel_y    — 가속도 Y축
 *   /sensor/accel_z    — 가속도 Z축
 *   /sensor/skin_temp  — 피부 표면 온도 (°C)
 *   /sensor/ecg        — ECG Raw
 *   /sensor/spo2       — 혈중 산소포화도 (%)
 *   /sensor/bia_fat    — 체지방률 (%)
 *   /sensor/bia_bmr    — 기초대사량
 *   /sensor/bia_muscle — 골격근량 (kg)
 *   /sensor/bia_water  — 체수분 (%)
 *   /sensor/sweat_loss — 발한량 (ml)
 */
package com.example.healthsensor

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

class WearDataListenerService : WearableListenerService() {

    private val TAG = "WearDataListener"

    override fun onPeerConnected(peer: Node) {
        WatchPeerConnectionEvents.publish()
    }

    override fun onPeerDisconnected(peer: Node) {
        WatchPeerConnectionEvents.publish()
    }

    override fun onMessageReceived(event: MessageEvent) {
        try {
            val buf  = ByteBuffer.wrap(event.data)
            val size = event.data.size
            Log.d(TAG, "${event.path} size=$size")

            when (event.path) {
                "/sensor/hr" -> when (size) {
                    12   -> SensorRepository.onHr(buf.long, buf.int.toFloat())   // 구형 호환
                    else -> parseBatch(buf) { ts, v -> SensorRepository.onHr(ts, v) }
                }
                "/sensor/ppg" -> when (size) {
                    12, 20 -> SensorRepository.onPpg(buf.long, buf.int.toFloat())
                    else   -> parseBatch(buf) { ts, v -> SensorRepository.onPpg(ts, v) }
                }
                "/sensor/ppg_ir" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onPpgIr(ts, v) }
                "/sensor/ppg_red" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onPpgRed(ts, v) }
                "/sensor/eda" -> when (size) {
                    12   -> SensorRepository.onEda(buf.long, buf.float)
                    else -> parseBatch(buf) { ts, v -> SensorRepository.onEda(ts, v) }
                }
                "/sensor/accel_x" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onAccelX(ts, v) }
                "/sensor/accel_y" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onAccelY(ts, v) }
                "/sensor/accel_z" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onAccelZ(ts, v) }
                "/sensor/skin_temp" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onSkinTemp(ts, v) }
                "/sensor/ecg" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onEcg(ts, v) }
                "/sensor/spo2" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onSpo2(ts, v) }
                "/sensor/bia_fat" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onBiaFat(ts, v) }
                "/sensor/bia_bmr", "/sensor/bia_bmi" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onBiaBmi(ts, v) }
                "/sensor/bia_muscle" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onBiaMuscle(ts, v) }
                "/sensor/bia_water" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onBiaWater(ts, v) }
                "/sensor/sweat_loss" ->
                    parseBatch(buf) { ts, v -> SensorRepository.onSweatLoss(ts, v) }
                // 구형 단일 채널 가속도 (magnitude) — 역호환용
                "/sensor/accel" -> when (size) {
                    20   -> {
                        val ts = buf.long; val x = buf.float; val y = buf.float; val z = buf.float
                        SensorRepository.onAccelX(ts, x)
                        SensorRepository.onAccelY(ts, y)
                        SensorRepository.onAccelZ(ts, z)
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "${event.path} 파싱 실패: ${e.message}")
        }
    }

    /** 배치 메시지를 순회하며 각 샘플을 handler에 전달한다. */
    private fun parseBatch(buf: ByteBuffer, handler: (Long, Float) -> Unit) {
        if (buf.remaining() < 4) return
        val count = buf.int
        if (count <= 0 || count > 10_000) return   // 비정상 count 방어
        val expectedBytes = count * 12
        if (buf.remaining() != expectedBytes) {
            Log.w(TAG, "배치 크기 불일치: count=$count remaining=${buf.remaining()} expected=$expectedBytes")
            return
        }
        repeat(count) { handler(buf.long, buf.float) }
    }
}
