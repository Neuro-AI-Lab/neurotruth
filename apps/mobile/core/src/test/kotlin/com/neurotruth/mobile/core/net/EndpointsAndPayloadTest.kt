package com.neurotruth.mobile.core.net

import com.neurotruth.mobile.core.SensorWindowContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointsAndPayloadTest {

    private val endpoints = ApiEndpoints("http://localhost:58441/")
    private val sessionId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

    @Test
    fun `every endpoint stays on the neurotruth backend`() {
        val urls = listOf(
            endpoints.patientSignup, endpoints.login, endpoints.refresh, endpoints.logout,
            endpoints.me, endpoints.consents, endpoints.sensorWindows, endpoints.predictionStream,
            endpoints.sessions, endpoints.sttStatus, endpoints.rppgStatus, endpoints.rppgJobs,
            endpoints.session(sessionId), endpoints.messages(sessionId),
            endpoints.assessments(sessionId), endpoints.finish(sessionId),
            endpoints.cravingProbabilitySeries("1h"),
        )
        assertTrue(urls.all { it.startsWith("http://localhost:58441/api/") })
        assertTrue(urls.none { it.contains("admin") })
    }

    @Test
    fun `a trailing slash on the base url does not double up`() {
        assertEquals("http://localhost:58441/api/me", endpoints.me)
    }

    @Test
    fun `a relative base url is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ApiEndpoints("localhost:58441") }
    }

    @Test
    fun `a malformed session id fails before the network`() {
        assertThrows(IllegalArgumentException::class.java) { endpoints.messages("not-a-uuid") }
    }

    @Test
    fun `the dashboard query encodes its timezone`() {
        val url = endpoints.cravingDashboard("Asia/Seoul", "7d", "today")
        assertTrue(url.contains("timezone=Asia%2FSeoul"))
        assertTrue(url.contains("eventRange=7d"))
        assertTrue(url.contains("auqRange=today"))
    }

    @Test
    fun `the recent hour series is requested as a one hour range`() {
        assertTrue(endpoints.cravingProbabilitySeries("1h").endsWith("craving-probability-series?range=1h"))
    }

    private fun window(
        start: Long = 1_784_160_050_000L,
        end: Long = 1_784_160_070_000L,
    ) = SensorWindowPayload(
        clientWindowId = "8e20b8d2-cd87-4be2-b54f-ed2e00472329",
        sessionStartedAtMs = 1_784_160_000_000L,
        sequence = 7L,
        sentAtMs = 1_784_160_070_123L,
        windowStartMs = start,
        windowEndMs = end,
        samples = listOf(
            SensorSample(SensorChannel.PPG_GREEN, start, 32_451.0f),
            SensorSample(SensorChannel.EDA, start, 0.27f),
        ),
    )

    @Test
    fun `the upload body matches the sensor window contract`() {
        val body = JSONObject(window().toJson())

        assertEquals("8e20b8d2-cd87-4be2-b54f-ed2e00472329", body.getString("clientWindowId"))
        assertEquals(20_000L, body.getLong("windowMs"))
        assertEquals(
            body.getLong("windowEndMs") - body.getLong("windowStartMs"),
            body.getLong("windowMs"),
        )
        assertEquals(7L, body.getLong("sequence"))

        val sync = body.getJSONObject("sync")
        assertEquals(SensorWindowContract.SYNC_MODE, sync.getString("mode"))
        assertEquals(SensorWindowContract.SYNC_FILL_MODE, sync.getString("fillMode"))
        assertEquals(25, sync.getInt("ppgHz"))
        assertEquals(500, sync.getInt("ppgSamplesPerChannel"))
        assertEquals(1, sync.getInt("edaHz"))
        assertEquals(20, sync.getInt("edaSamples"))

        assertEquals(2, body.getJSONArray("samples").length())
        // sessionId is local bookkeeping and must not reach the request body.
        assertFalse(body.has("sessionId"))
    }

    @Test
    fun `a window outside the accepted span cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) { window(end = 1_784_160_050_000L + 19_000L) }
        assertThrows(IllegalArgumentException::class.java) { window(end = 1_784_160_050_000L + 21_000L) }
    }

    @Test
    fun `a retry serializes byte-identically`() {
        assertEquals(window().toJson(), window().toJson())
    }

    @Test
    fun `resampling lands on the declared fixed grid`() {
        val raw = (0 until 40).map {
            SensorSample(SensorChannel.EDA, it * 500L, it.toFloat())
        }
        val grid = FixedGridResampler.resample(
            raw,
            SensorChannel.EDA,
            windowStartMs = 0L,
            intervalMs = SensorWindowContract.EDA_SAMPLE_INTERVAL_MS,
            count = SensorWindowContract.EDA_SAMPLES,
        )
        assertEquals(20, grid.size)
        assertEquals(0L, grid.first().timestampMs)
        assertEquals(19_000L, grid.last().timestampMs)
        assertEquals(2.0f, grid[1].value, 0.001f)
    }

    @Test
    fun `resampling interpolates between bracketing samples`() {
        val raw = listOf(
            SensorSample(SensorChannel.EDA, 0L, 0f),
            SensorSample(SensorChannel.EDA, 2_000L, 2f),
        )
        val grid = FixedGridResampler.resample(raw, SensorChannel.EDA, 0L, 1_000L, 3)
        assertEquals(1.0f, grid[1].value, 0.001f)
    }

    @Test
    fun `a wide gap holds the nearest value instead of ramping through it`() {
        val raw = listOf(
            SensorSample(SensorChannel.EDA, 0L, 0f),
            SensorSample(SensorChannel.EDA, 10_000L, 10f),
        )
        val grid = FixedGridResampler.resample(raw, SensorChannel.EDA, 0L, 1_000L, 11)

        // A linear ramp would put 1.0 here and 8.0 at index 8; the hold keeps the real edges.
        assertEquals(0f, grid[1].value, 0.001f)
        assertEquals(0f, grid[5].value, 0.001f)
        assertEquals(10f, grid[8].value, 0.001f)
    }

    @Test
    fun `an absent channel yields no samples rather than zeros`() {
        val grid = FixedGridResampler.resample(emptyList(), SensorChannel.PPG_IR, 0L, 40L, 500)
        assertTrue(grid.isEmpty())
    }
}
