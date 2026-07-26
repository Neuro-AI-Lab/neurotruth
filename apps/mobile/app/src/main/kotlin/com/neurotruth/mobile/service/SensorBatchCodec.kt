package com.neurotruth.mobile.service

import com.neurotruth.mobile.core.net.SensorChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One decoded sample. Channel identity comes from the Data Layer path, not from the payload. */
data class TimedSample(val timestampMs: Long, val value: Float)

/**
 * Decodes the batched binary Data Layer payload of PRD §5.5.
 *
 * ```text
 * [count : Int32] then count × ( [timestampMs : Int64] [value : Float32] )
 * total bytes = 4 + count * 12
 * ```
 *
 * The watch writes this with a default-order [ByteBuffer], so it is big-endian on both sides.
 *
 * Every rejection returns `null` rather than throwing: this runs inside a `WearableListenerService`
 * callback, where a malformed batch from one channel must not take down the others. A truncated or
 * oversized batch is dropped whole — partially decoding it would silently feed the upload window
 * samples that were never measured.
 *
 * This object holds no Android types so the contract stays testable on the JVM.
 */
object SensorBatchCodec {
    const val HEADER_BYTES: Int = 4
    const val SAMPLE_BYTES: Int = 12

    /** A single two-second flush remains far below this cap; anything near it is corrupt. */
    const val MAX_SAMPLES: Int = 10_000

    const val PATH_HR = "/sensor-v2/hr"
    const val PATH_PPG = "/sensor-v2/ppg"
    const val PATH_PPG_IR = "/sensor-v2/ppg_ir"
    const val PATH_PPG_RED = "/sensor-v2/ppg_red"
    const val PATH_EDA = "/sensor-v2/eda"
    const val PATH_ACCEL_X = "/sensor-v2/accel_x"
    const val PATH_ACCEL_Y = "/sensor-v2/accel_y"
    const val PATH_ACCEL_Z = "/sensor-v2/accel_z"
    const val PATH_SKIN_TEMP = "/sensor-v2/skin_temp"

    private val CHANNEL_BY_PATH: Map<String, String> = mapOf(
        PATH_HR to SensorChannel.HR,
        PATH_PPG to SensorChannel.PPG_GREEN,
        PATH_PPG_IR to SensorChannel.PPG_IR,
        PATH_PPG_RED to SensorChannel.PPG_RED,
        PATH_EDA to SensorChannel.EDA,
        PATH_ACCEL_X to SensorChannel.ACCEL_X,
        PATH_ACCEL_Y to SensorChannel.ACCEL_Y,
        PATH_ACCEL_Z to SensorChannel.ACCEL_Z,
        PATH_SKIN_TEMP to SensorChannel.SKIN_TEMP,
    )

    val paths: Set<String> get() = CHANNEL_BY_PATH.keys

    /** Returns the upload-contract channel name, or `null` for a path this app does not accept. */
    fun channelFor(path: String): String? = CHANNEL_BY_PATH[path]

    /** Returns the decoded batch, or `null` if the payload violates the contract. */
    fun decode(payload: ByteArray?): List<TimedSample>? {
        if (payload == null || payload.size < HEADER_BYTES) return null

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.int
        if (count <= 0 || count > MAX_SAMPLES) return null
        if (buffer.remaining() != count * SAMPLE_BYTES) return null

        val samples = ArrayList<TimedSample>(count)
        repeat(count) {
            val timestampMs = buffer.long
            val value = buffer.float
            samples.add(TimedSample(timestampMs, value))
        }
        return samples
    }
}
