package com.neurotruth.mobile.service

/**
 * Validates one Watch Data Layer message and records its fresh samples.
 *
 * [MonitoringService] owns the live listener because it is already a foreground service whenever
 * biosignal monitoring is active. This avoids exporting a second bindable Android service.
 */
internal object WearSensorMessageHandler {
    fun record(path: String, data: ByteArray, nowMs: Long): SensorBatchReceipt? {
        val channel = SensorBatchCodec.channelFor(path) ?: return null
        val decoded = SensorBatchCodec.decode(data)
        if (decoded == null) {
            return null
        }
        // MessageClient delivery may resume after a disconnect. Historical samples must not be
        // mistaken for the current 20-second craving window.
        val samples = freshSensorSamples(decoded, nowMs)
        if (samples.isEmpty()) return null
        SensorSampleRepository.record(channel, samples)
        return SensorBatchReceipt(
            path = path,
            count = samples.size,
            newestTimestampMs = samples.last().timestampMs,
        )
    }
}

internal data class SensorBatchReceipt(
    val path: String,
    val count: Int,
    val newestTimestampMs: Long,
)

internal fun freshSensorSamples(
    samples: List<TimedSample>,
    nowMs: Long,
): List<TimedSample> {
    val oldestAllowed = nowMs - MAX_SENSOR_BATCH_AGE_MS
    val newestAllowed = nowMs + MAX_SENSOR_CLOCK_SKEW_MS
    return samples.filter { it.timestampMs in oldestAllowed..newestAllowed }
}

private const val MAX_SENSOR_BATCH_AGE_MS = 30_000L
private const val MAX_SENSOR_CLOCK_SKEW_MS = 5_000L
