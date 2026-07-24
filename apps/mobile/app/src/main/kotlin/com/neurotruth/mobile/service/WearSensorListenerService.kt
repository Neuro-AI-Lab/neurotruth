package com.neurotruth.mobile.service

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the watch's batched sensor payloads and feeds [SensorSampleRepository].
 *
 * The watch streams samples; it holds no backend credentials and never calls the backend. All
 * windowing, authentication and upload happen on this side, in [MonitoringService].
 *
 * Decoding and validation live in [SensorBatchCodec] so the contract of PRD §5.5 is covered by JVM
 * unit tests rather than only by an emulator.
 */
class WearSensorListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val channel = SensorBatchCodec.channelFor(event.path) ?: return
        val samples = SensorBatchCodec.decode(event.data)
        if (samples == null) {
            // Length, count and bounds are all contract violations. Drop the batch whole; the next
            // flush arrives in ~200 ms and a gap is preferable to fabricated samples.
            Log.w(TAG, "잘못된 센서 배치 수신 [${event.path}]")
            return
        }
        SensorSampleRepository.record(channel, samples)
    }

    private companion object {
        const val TAG = "WearSensorListener"
    }
}
