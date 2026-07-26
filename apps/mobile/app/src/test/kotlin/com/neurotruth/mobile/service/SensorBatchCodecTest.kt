package com.neurotruth.mobile.service

import com.neurotruth.mobile.core.net.SensorChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The batched Data Layer format of PRD §5.5.
 *
 * Plain JUnit with no Robolectric: [SensorBatchCodec] holds no Android types precisely so this
 * contract can be pinned without an emulator.
 */
class SensorBatchCodecTest {

    private fun encode(samples: List<Pair<Long, Float>>, declaredCount: Int = samples.size): ByteArray {
        val buffer = ByteBuffer.allocate(4 + samples.size * 12)
        buffer.putInt(declaredCount)
        for ((timestampMs, value) in samples) {
            buffer.putLong(timestampMs)
            buffer.putFloat(value)
        }
        return buffer.array()
    }

    @Test
    fun `decodes a valid batch`() {
        val payload = encode(
            listOf(
                1_784_160_000_000L to 1234.5f,
                1_784_160_000_040L to 1240.25f,
                1_784_160_000_080L to -3.5f,
            ),
        )

        assertEquals(4 + 3 * 12, payload.size)

        val decoded = SensorBatchCodec.decode(payload)
        assertEquals(3, decoded?.size)
        assertEquals(1_784_160_000_000L, decoded!![0].timestampMs)
        assertEquals(1234.5f, decoded[0].value, 0f)
        assertEquals(1_784_160_000_080L, decoded[2].timestampMs)
        assertEquals(-3.5f, decoded[2].value, 0f)
    }

    @Test
    fun `decodes a single-sample batch`() {
        val decoded = SensorBatchCodec.decode(encode(listOf(7L to 0.5f)))
        assertEquals(1, decoded?.size)
        assertEquals(0.5f, decoded!![0].value, 0f)
    }

    @Test
    fun `rejects a count that does not match the buffer length`() {
        // Three samples on the wire, four declared: 4 + 4*12 != payload.size.
        val payload = encode(
            samples = listOf(1L to 1f, 2L to 2f, 3L to 3f),
            declaredCount = 4,
        )
        assertNull(SensorBatchCodec.decode(payload))
    }

    @Test
    fun `rejects a trailing byte beyond the declared count`() {
        val payload = encode(listOf(1L to 1f)) + byteArrayOf(0)
        assertNull(SensorBatchCodec.decode(payload))
    }

    @Test
    fun `rejects an oversized count`() {
        val payload = ByteBuffer.allocate(4).putInt(SensorBatchCodec.MAX_SAMPLES + 1).array()
        assertNull(SensorBatchCodec.decode(payload))
    }

    @Test
    fun `rejects a non-positive count`() {
        assertNull(SensorBatchCodec.decode(ByteBuffer.allocate(4).putInt(0).array()))
        assertNull(SensorBatchCodec.decode(ByteBuffer.allocate(4).putInt(-1).array()))
    }

    @Test
    fun `rejects a payload shorter than the header`() {
        assertNull(SensorBatchCodec.decode(ByteArray(0)))
        assertNull(SensorBatchCodec.decode(ByteArray(3)))
        assertNull(SensorBatchCodec.decode(null))
    }

    @Test
    fun `maps every declared sensor path to an upload channel`() {
        assertEquals(9, SensorBatchCodec.paths.size)
        assertEquals(SensorChannel.HR, SensorBatchCodec.channelFor("/sensor-v2/hr"))
        assertEquals(SensorChannel.PPG_GREEN, SensorBatchCodec.channelFor("/sensor-v2/ppg"))
        assertEquals(SensorChannel.PPG_IR, SensorBatchCodec.channelFor("/sensor-v2/ppg_ir"))
        assertEquals(SensorChannel.PPG_RED, SensorBatchCodec.channelFor("/sensor-v2/ppg_red"))
        assertEquals(SensorChannel.EDA, SensorBatchCodec.channelFor("/sensor-v2/eda"))
        assertEquals(SensorChannel.ACCEL_X, SensorBatchCodec.channelFor("/sensor-v2/accel_x"))
        assertEquals(SensorChannel.ACCEL_Y, SensorBatchCodec.channelFor("/sensor-v2/accel_y"))
        assertEquals(SensorChannel.ACCEL_Z, SensorBatchCodec.channelFor("/sensor-v2/accel_z"))
        assertEquals(SensorChannel.SKIN_TEMP, SensorBatchCodec.channelFor("/sensor-v2/skin_temp"))
    }

    @Test
    fun `ignores an unknown path`() {
        assertNull(SensorBatchCodec.channelFor("/sensor/ecg"))
        assertNull(SensorBatchCodec.channelFor("/sensor/ppg"))
        assertNull(SensorBatchCodec.channelFor("/prediction/class"))
    }

    @Test
    fun `accepts a full flush cycle at the ceiling`() {
        val samples = (0 until SensorBatchCodec.MAX_SAMPLES).map { it.toLong() to it.toFloat() }
        val decoded = SensorBatchCodec.decode(encode(samples))
        assertTrue(decoded != null && decoded.size == SensorBatchCodec.MAX_SAMPLES)
    }

    @Test
    fun `keeps only samples close to the phone clock`() {
        val now = 1_000_000L
        val samples = listOf(
            TimedSample(now - 30_001L, 1f),
            TimedSample(now - 30_000L, 2f),
            TimedSample(now, 3f),
            TimedSample(now + 5_000L, 4f),
            TimedSample(now + 5_001L, 5f),
        )

        assertEquals(
            listOf(samples[1], samples[2], samples[3]),
            freshSensorSamples(samples, now),
        )
    }

    @Test
    fun `drops an entirely stale queued batch`() {
        val now = 1_000_000L
        assertTrue(
            freshSensorSamples(
                listOf(TimedSample(now - 60_000L, 1f)),
                now,
            ).isEmpty(),
        )
    }

    @Test
    fun `message handler records a fresh versioned batch`() {
        val now = 1_000_000L
        SensorSampleRepository.clear()

        val receipt = WearSensorMessageHandler.record(
            path = SensorBatchCodec.PATH_PPG,
            data = encode(listOf((now - 20L) to 1f, now to 2f)),
            nowMs = now,
        )

        assertEquals(SensorBatchCodec.PATH_PPG, receipt?.path)
        assertEquals(2, receipt?.count)
        assertEquals(now, receipt?.newestTimestampMs)
        assertEquals(2, SensorSampleRepository.samplesIn(now - 20L, now).size)
        SensorSampleRepository.clear()
    }
}
