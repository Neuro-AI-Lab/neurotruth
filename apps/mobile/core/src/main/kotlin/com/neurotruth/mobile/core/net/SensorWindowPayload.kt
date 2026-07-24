package com.neurotruth.mobile.core.net

import com.neurotruth.mobile.core.SensorWindowContract
import org.json.JSONArray
import org.json.JSONObject

data class SensorSample(
    val sensor: String,
    val timestampMs: Long,
    val value: Float,
)

/**
 * One 20-second window for `POST /api/sensor-windows`.
 *
 * A retry must re-send this byte-identical under the same [clientWindowId]; different content under
 * the same id is a 409. The phone owns the fixed grid the [SensorWindowContract] sync block
 * declares — the watch emits at its native rates and this payload is already resampled.
 */
data class SensorWindowPayload(
    val clientWindowId: String,
    val sessionStartedAtMs: Long,
    val sequence: Long,
    val sentAtMs: Long,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val samples: List<SensorSample>,
) {
    init {
        require(clientWindowId.isNotBlank()) { "clientWindowId must not be blank" }
        require(SensorWindowContract.isValidWindow(windowStartMs, windowEndMs)) {
            "window span must be within ${SensorWindowContract.MIN_WINDOW_MS}" +
                "..${SensorWindowContract.MAX_WINDOW_MS} ms"
        }
        require(samples.isNotEmpty()) { "a window must carry samples" }
    }

    val windowMs: Long get() = windowEndMs - windowStartMs

    fun toJson(): String {
        val sync = JSONObject()
            .put("mode", SensorWindowContract.SYNC_MODE)
            .put("fillMode", SensorWindowContract.SYNC_FILL_MODE)
            .put("ppgHz", SensorWindowContract.PPG_HZ)
            .put("ppgSamplesPerChannel", SensorWindowContract.PPG_SAMPLES_PER_CHANNEL)
            .put("edaHz", SensorWindowContract.EDA_HZ)
            .put("edaSamples", SensorWindowContract.EDA_SAMPLES)

        val sampleArray = JSONArray()
        for (sample in samples) {
            sampleArray.put(
                JSONObject()
                    .put("sensor", sample.sensor)
                    .put("timestampMs", sample.timestampMs)
                    .put("value", sample.value.toDouble()),
            )
        }

        return JSONObject()
            .put("clientWindowId", clientWindowId)
            .put("sessionStartedAtMs", sessionStartedAtMs)
            .put("sequence", sequence)
            .put("sentAtMs", sentAtMs)
            .put("windowStartMs", windowStartMs)
            .put("windowEndMs", windowEndMs)
            .put("windowMs", windowMs)
            .put("sync", sync)
            .put("samples", sampleArray)
            .toString()
    }
}

object SensorChannel {
    const val HR = "HR"
    const val PPG_GREEN = "PPG_GREEN"
    const val PPG_IR = "PPG_IR"
    const val PPG_RED = "PPG_RED"
    const val EDA = "EDA"
    const val ACCEL_X = "ACCEL_X"
    const val ACCEL_Y = "ACCEL_Y"
    const val ACCEL_Z = "ACCEL_Z"
    const val SKIN_TEMP = "SKIN_TEMP"
}

/**
 * Resamples a channel onto the fixed grid the upload contract declares.
 *
 * Interpolates linearly between bracketing samples, holds the nearest value across a gap wider than
 * three intervals rather than inventing a ramp through it, and holds at the edges.
 */
object FixedGridResampler {
    fun resample(
        samples: List<SensorSample>,
        sensor: String,
        windowStartMs: Long,
        intervalMs: Long,
        count: Int,
    ): List<SensorSample> {
        require(intervalMs > 0) { "intervalMs must be positive" }
        require(count > 0) { "count must be positive" }
        val ordered = samples.filter { it.sensor == sensor }.sortedBy { it.timestampMs }
        if (ordered.isEmpty()) return emptyList()

        val gapLimit = intervalMs * 3
        val output = ArrayList<SensorSample>(count)
        var cursor = 0
        for (index in 0 until count) {
            val targetMs = windowStartMs + index * intervalMs
            while (cursor < ordered.size - 1 && ordered[cursor + 1].timestampMs <= targetMs) cursor++

            val current = ordered[cursor]
            val next = ordered.getOrNull(cursor + 1)
            val value = when {
                targetMs <= current.timestampMs -> current.value
                next == null -> current.value
                next.timestampMs - current.timestampMs > gapLimit ->
                    if (targetMs - current.timestampMs <= next.timestampMs - targetMs) {
                        current.value
                    } else {
                        next.value
                    }
                else -> {
                    val span = (next.timestampMs - current.timestampMs).toFloat()
                    val ratio = (targetMs - current.timestampMs) / span
                    current.value + (next.value - current.value) * ratio
                }
            }
            output.add(SensorSample(sensor, targetMs, value))
        }
        return output
    }
}
