package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.net.SensorChannel
import com.neurotruth.mobile.core.net.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live Watch signal section.
 *
 * Display processing is isolated from upload persistence: both channels use the existing fixed grid,
 * are normalized independently, and missing or stale input never becomes a fabricated zero trace.
 */
class LivePpgWindowTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `fresh channels use the upload grids and independent MinMax ranges`() {
        val ppg = channelSamples(SensorChannel.PPG_GREEN, 501, now - 20_000, 40L) {
            10f + it
        }
        val eda = channelSamples(SensorChannel.EDA, 21, now - 20_000, 1_000L) {
            1_000f + it * 10f
        }

        val trace = LivePpgWindow.build(ppg + eda, nowMs = now)

        assertNotNull(trace)
        assertEquals(LivePpgWindow.PPG_POINTS, trace!!.samples.size)
        assertEquals(LivePpgWindow.EDA_POINTS, trace.edaSamples.size)
        assertEquals(now - 20_000, trace.samples.first().atMs)
        assertEquals(now - 40, trace.samples.last().atMs)
        assertEquals(now - 1_000, trace.edaSamples.last().atMs)
        assertEquals(0f, trace.samples.minOf(PpgSample::value), 1e-6f)
        assertEquals(1f, trace.samples.maxOf(PpgSample::value), 1e-6f)
        assertEquals(0f, trace.edaSamples.minOf(PpgSample::value), 1e-6f)
        assertEquals(1f, trace.edaSamples.maxOf(PpgSample::value), 1e-6f)
        assertEquals(now, trace.latestAtMs)
    }

    @Test
    fun `constant channels become display zeros on their own grids`() {
        val ppg = channelSamples(SensorChannel.PPG_GREEN, 501, now - 20_000, 40L) { 7f }
        val eda = channelSamples(SensorChannel.EDA, 21, now - 20_000, 1_000L) { 0.4f }

        val trace = LivePpgWindow.build(ppg + eda, nowMs = now)!!

        assertTrue(trace.samples.all { it.value == 0f })
        assertTrue(trace.edaSamples.all { it.value == 0f })
    }

    @Test
    fun `missing EDA stays empty while fresh PPG remains visible`() {
        val ppg = channelSamples(SensorChannel.PPG_GREEN, 501, now - 20_000, 40L)
        val trace = LivePpgWindow.build(ppg, nowMs = now)

        assertNotNull(trace)
        assertEquals(LivePpgWindow.PPG_POINTS, trace!!.samples.size)
        assertTrue(trace.edaSamples.isEmpty())
    }

    @Test
    fun `missing PPG stays empty while fresh EDA remains visible`() {
        val eda = channelSamples(SensorChannel.EDA, 21, now - 20_000, 1_000L)
        val trace = LivePpgWindow.build(eda, nowMs = now)

        assertNotNull(trace)
        assertTrue(trace!!.samples.isEmpty())
        assertEquals(LivePpgWindow.EDA_POINTS, trace.edaSamples.size)
    }

    @Test
    fun `other PPG channels never enter the green trace`() {
        val green = channelSamples(SensorChannel.PPG_GREEN, 501, now - 20_000, 40L)
        val red = channelSamples(SensorChannel.PPG_RED, 501, now - 20_000, 40L) { 99f }

        val trace = LivePpgWindow.build(green + red, nowMs = now)!!

        assertEquals(LivePpgWindow.PPG_POINTS, trace.samples.size)
        assertEquals(0f, trace.samples.minOf(PpgSample::value), 1e-6f)
        assertEquals(1f, trace.samples.maxOf(PpgSample::value), 1e-6f)
    }

    @Test
    fun `a stalled channel is omitted instead of remaining as a live zero trace`() {
        val stalePpg = channelSamples(SensorChannel.PPG_GREEN, 100, now - 10_000, 40L)
        val freshEda = channelSamples(SensorChannel.EDA, 4, now - 3_000, 1_000L)

        val trace = LivePpgWindow.build(stalePpg + freshEda, nowMs = now)

        assertNotNull(trace)
        assertTrue(trace!!.samples.isEmpty())
        assertEquals(LivePpgWindow.EDA_POINTS, trace.edaSamples.size)
        assertFalse(LivePpgWindow.isFresh(stalePpg.last().timestampMs, now))
    }

    @Test
    fun `empty or wholly stale buffers produce no trace`() {
        assertNull(LivePpgWindow.build(emptyList(), nowMs = now))
        val stale = channelSamples(SensorChannel.PPG_GREEN, 100, now - 20_000, 40L)
        assertNull(LivePpgWindow.build(stale, nowMs = now))
        assertFalse(LivePpgWindow.isFresh(null, now))
    }

    private fun channelSamples(
        sensor: String,
        count: Int,
        startMs: Long,
        stepMs: Long,
        valueAt: (Int) -> Float = { index -> 0.5f + (index % 25) * 0.02f },
    ): List<SensorSample> =
        (0 until count).map { index ->
            SensorSample(
                sensor = sensor,
                timestampMs = startMs + index * stepMs,
                value = valueAt(index),
            )
        }
}
