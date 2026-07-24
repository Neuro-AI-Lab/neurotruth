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
 * The live half of NT-08's PPG section.
 *
 * The rules under test are the ones a user could be misled by: the display cap, an empty buffer, and
 * a buffer that has stopped advancing but still holds samples.
 */
class LivePpgWindowTest {

    private val now = 1_800_000_000_000L

    // -----------------------------------------------------------------------------------------
    // downsampling
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a buffer within the cap is returned untouched`() {
        val samples = ppgSamples(count = 500, startMs = now - 20_000)
        val trace = LivePpgWindow.build(samples, nowMs = now)
        assertNotNull(trace)
        assertEquals(500, trace!!.samples.size)
    }

    @Test
    fun `a buffer beyond the cap is downsampled to exactly 512 points`() {
        // The buffer's full retention (~1,200 samples) packed into the 20-second display window.
        val samples = ppgSamples(count = 1_200, startMs = now - 20_000, stepMs = 16L)
        val trace = LivePpgWindow.build(samples, nowMs = now)
        assertNotNull(trace)
        assertEquals(LivePpgWindow.MAX_POINTS, trace!!.samples.size)
    }

    @Test
    fun `downsampling keeps both ends and stays chronological`() {
        val samples = (0 until 2_000).map { PpgSample(atMs = now - 2_000 + it, value = it.toFloat()) }
        val reduced = LivePpgWindow.downsample(samples)
        assertEquals(LivePpgWindow.MAX_POINTS, reduced.size)
        assertEquals(samples.first(), reduced.first())
        assertEquals(samples.last(), reduced.last())
        assertTrue(reduced.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
    }

    @Test
    fun `downsampling never pads a short buffer up to the cap`() {
        val samples = (0 until 3).map { PpgSample(atMs = now + it, value = 1f) }
        assertEquals(3, LivePpgWindow.downsample(samples).size)
        assertTrue(LivePpgWindow.downsample(emptyList()).isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // empty and stale buffers
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an empty buffer produces no trace`() {
        assertNull(LivePpgWindow.build(emptyList(), nowMs = now))
    }

    @Test
    fun `a buffer with no PPG_GREEN samples produces no trace`() {
        val samples = (0 until 100).map {
            SensorSample(SensorChannel.EDA, now - 10_000 + it * 100L, 0.4f)
        }
        assertNull(LivePpgWindow.build(samples, nowMs = now))
    }

    @Test
    fun `a stalled buffer is not presented as live`() {
        // Real samples, but the newest is older than the staleness threshold.
        val samples = ppgSamples(count = 200, startMs = now - 19_000, stepMs = 40L)
        val latest = samples.maxOf(SensorSample::timestampMs)
        assertTrue(now - latest > LivePpgWindow.STALE_AFTER_MS)
        assertNull(LivePpgWindow.build(samples, nowMs = now))
        assertFalse(LivePpgWindow.isFresh(latest, now))
    }

    @Test
    fun `a buffer that is still advancing is live`() {
        val samples = ppgSamples(count = 500, startMs = now - 20_000)
        val latest = samples.maxOf(SensorSample::timestampMs)
        assertTrue(LivePpgWindow.isFresh(latest, now))
        assertEquals(latest, LivePpgWindow.build(samples, nowMs = now)!!.latestAtMs)
    }

    @Test
    fun `samples outside the window are dropped`() {
        val old = ppgSamples(count = 100, startMs = now - 120_000, stepMs = 40L)
        val recent = ppgSamples(count = 250, startMs = now - 10_000)
        val trace = LivePpgWindow.build(old + recent, nowMs = now)
        assertNotNull(trace)
        assertTrue(trace!!.samples.all { it.atMs >= now - LivePpgWindow.WINDOW_MS })
        assertEquals(250, trace.samples.size)
    }

    @Test
    fun `only PPG_GREEN is plotted`() {
        val green = ppgSamples(count = 100, startMs = now - 4_000)
        val red = green.map { it.copy(sensor = SensorChannel.PPG_RED, value = 99f) }
        val trace = LivePpgWindow.build(green + red, nowMs = now)
        assertNotNull(trace)
        assertEquals(100, trace!!.samples.size)
        assertTrue(trace.samples.none { it.value == 99f })
    }

    @Test
    fun `a null latest timestamp is never fresh`() {
        assertFalse(LivePpgWindow.isFresh(null, now))
    }

    /** 25 Hz PPG ending at `startMs + count * stepMs`. */
    private fun ppgSamples(count: Int, startMs: Long, stepMs: Long = 40L): List<SensorSample> =
        (0 until count).map { index ->
            SensorSample(
                sensor = SensorChannel.PPG_GREEN,
                timestampMs = startMs + index * stepMs,
                value = 0.5f + (index % 25) * 0.02f,
            )
        }
}
