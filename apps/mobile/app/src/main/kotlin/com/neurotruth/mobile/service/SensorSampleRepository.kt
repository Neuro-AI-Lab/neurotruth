package com.neurotruth.mobile.service

import com.neurotruth.mobile.core.SensorWindowContract
import com.neurotruth.mobile.core.net.SensorChannel
import com.neurotruth.mobile.core.net.SensorSample

/**
 * Per-channel ring buffers holding the most recent watch samples.
 *
 * A singleton because `WearSensorListenerService` is constructed by the framework on message
 * delivery while `MonitoringService` reads the buffers on its own 10-second tick; the two never hold
 * a reference to each other.
 *
 * Retention is bounded per channel. The scheduler only ever needs the last 20 seconds, so a
 * generous multiple of that is kept — enough to absorb a late flush, far short of unbounded growth
 * during a long session.
 */
object SensorSampleRepository {

    /** ~48 s of 25 Hz PPG. The scheduler reads the last 20 s. */
    private const val CAPACITY_HIGH_RATE = 1_200

    /** ~10 min of 1 Hz EDA, HR and skin temperature. */
    private const val CAPACITY_LOW_RATE = 600

    private val buffers = LinkedHashMap<String, ArrayDeque<SensorSample>>()

    private fun capacityFor(channel: String): Int = when (channel) {
        SensorChannel.EDA, SensorChannel.HR, SensorChannel.SKIN_TEMP -> CAPACITY_LOW_RATE
        else -> CAPACITY_HIGH_RATE
    }

    @Synchronized
    fun record(channel: String, samples: List<TimedSample>) {
        if (samples.isEmpty()) return
        val buffer = buffers.getOrPut(channel) { ArrayDeque() }
        val capacity = capacityFor(channel)
        for (sample in samples) {
            buffer.addLast(SensorSample(channel, sample.timestampMs, sample.value))
        }
        while (buffer.size > capacity) buffer.removeFirst()
    }

    /** Every retained sample whose timestamp falls inside the window, across all channels. */
    @Synchronized
    fun samplesIn(windowStartMs: Long, windowEndMs: Long): List<SensorSample> =
        buffers.values.flatten().filter { it.timestampMs in windowStartMs..windowEndMs }

    /** Newest sample timestamp seen on any channel, or `null` if nothing has arrived. */
    @Synchronized
    fun latestTimestampMs(): Long? =
        buffers.values.mapNotNull { it.lastOrNull()?.timestampMs }.maxOrNull()

    /** True once each PPG channel plus EDA carries something inside the window. */
    @Synchronized
    fun hasRequiredChannelsIn(windowStartMs: Long, windowEndMs: Long): Boolean =
        REQUIRED_CHANNELS.all { channel ->
            buffers[channel]?.any { it.timestampMs in windowStartMs..windowEndMs } == true
        }

    @Synchronized
    fun clear() {
        buffers.clear()
    }

    /**
     * The channels the upload's `sync` block declares: three PPG channels at
     * [SensorWindowContract.PPG_HZ] and EDA at [SensorWindowContract.EDA_HZ].
     */
    val REQUIRED_CHANNELS: List<String> = listOf(
        SensorChannel.PPG_GREEN,
        SensorChannel.PPG_IR,
        SensorChannel.PPG_RED,
        SensorChannel.EDA,
    )
}
