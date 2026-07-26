package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.RppgContract
import com.neurotruth.mobile.core.RppgJobStatus
import com.neurotruth.mobile.core.SOURCE_CAMERA
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.AuthenticationRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RppgServiceStatusTest {

    @Test
    fun `ready requires all three flags`() {
        val status = RppgServiceStatus.parse(
            """{"enabled":true,"available":true,"modelLoaded":true}""",
        )
        assertTrue(status.ready)
    }

    @Test
    fun `a loaded model on a disabled feature is not ready`() {
        val status = RppgServiceStatus.parse(
            """{"enabled":false,"available":true,"modelLoaded":true}""",
        )
        assertFalse(status.ready)
    }

    @Test
    fun `an unavailable host is not ready`() {
        val status = RppgServiceStatus.parse(
            """{"enabled":true,"available":false,"modelLoaded":true}""",
        )
        assertFalse(status.ready)
    }

    @Test
    fun `an unloaded model is not ready`() {
        val status = RppgServiceStatus.parse(
            """{"enabled":true,"available":true,"modelLoaded":false}""",
        )
        assertFalse(status.ready)
    }

    /** `ready` stands in for the detail flags, but never for `enabled`. */
    @Test
    fun `ready fills in for absent detail flags only`() {
        val status = RppgServiceStatus.parse("""{"enabled":true,"ready":true}""")
        assertTrue(status.available)
        assertTrue(status.modelLoaded)
        assertTrue(status.ready)

        assertFalse(RppgServiceStatus.parse("""{"ready":true}""").ready)
    }

    @Test
    fun `missing fields are not ready`() {
        assertFalse(RppgServiceStatus.parse("{}").ready)
    }
}

class RppgJobSnapshotTest {

    private val jobId = "0f8f2a5c-1d3e-4c9a-9c1a-2b7d5e6f7a81"
    private val captureId = "11111111-2222-3333-4444-555555555555"

    @Test
    fun `an unknown status is not terminal`() {
        val snapshot = RppgJobSnapshot.parse(
            """{"jobId":"$jobId","captureId":"$captureId","status":"reticulating"}""",
        )
        assertNull(snapshot.status)
        assertFalse(snapshot.isTerminal)
    }

    @Test
    fun `queued and running are not terminal`() {
        listOf("queued", "running").forEach { status ->
            val snapshot = RppgJobSnapshot.parse(
                """{"jobId":"$jobId","captureId":"$captureId","status":"$status"}""",
            )
            assertFalse(status, snapshot.isTerminal)
        }
    }

    @Test
    fun `a completed job yields a camera prediction at the capture time`() {
        val snapshot = RppgJobSnapshot.parse(
            """
            {"jobId":"$jobId","captureId":"$captureId","status":"completed",
             "capturedAtMs":1700000000000,"cravingProbability":0.6,"alertAction":"recommend"}
            """.trimIndent(),
        )
        val prediction = requireNotNull(snapshot.toPrediction())
        assertEquals(0.6f, prediction.cravingProbability, 1e-6f)
        assertEquals(1_700_000_000_000L, prediction.timestampMs)
        assertEquals(SOURCE_CAMERA, prediction.source)
        assertEquals("recommend", prediction.alertAction)
    }

    /** `classIndex` is the binary classifier's own framing; it is folded into one probability. */
    @Test
    fun `classIndex and confidence fold into the probability of class one`() {
        val classOne = RppgJobSnapshot.parse(
            """{"jobId":"$jobId","captureId":"$captureId","status":"completed",
                "classIndex":1,"confidence":0.8}""",
        )
        assertEquals(0.8f, requireNotNull(classOne.toPrediction()).cravingProbability, 1e-6f)

        val classZero = RppgJobSnapshot.parse(
            """{"jobId":"$jobId","captureId":"$captureId","status":"completed",
                "classIndex":0,"confidence":0.8}""",
        )
        assertEquals(
            0.19999999f,
            requireNotNull(classZero.toPrediction()).cravingProbability,
            1e-5f,
        )
    }

    @Test
    fun `a non-completed job never produces a prediction`() {
        listOf("queued", "running", "retry_required", "failed").forEach { status ->
            val snapshot = RppgJobSnapshot.parse(
                """{"jobId":"$jobId","captureId":"$captureId","status":"$status",
                    "cravingProbability":0.6}""",
            )
            assertNull(status, snapshot.toPrediction())
        }
    }

    @Test
    fun `a completed job without a probability produces no prediction`() {
        val snapshot = RppgJobSnapshot.parse(
            """{"jobId":"$jobId","captureId":"$captureId","status":"completed"}""",
        )
        assertNull(snapshot.toPrediction())
    }

    @Test
    fun `retryAllowed is read for the failed branch`() {
        val snapshot = RppgJobSnapshot.parse(
            """{"jobId":"$jobId","captureId":"$captureId","status":"failed","retryAllowed":true}""",
        )
        assertEquals(RppgJobStatus.FAILED, snapshot.status)
        assertTrue(snapshot.retryAllowed)
    }

    @Test
    fun `a snapshot survives a round trip through its stored form`() {
        val original = RppgJobSnapshot.parse(
            """
            {"jobId":"$jobId","captureId":"$captureId","status":"completed",
             "capturedAtMs":1700000000000,"cravingProbability":0.42,"heartRateBpm":72.5,
             "qualityScore":0.9,"retryAllowed":false}
            """.trimIndent(),
        )
        assertEquals(original, RppgJobSnapshot.fromJson(original.toJson()))
    }
}

class RppgAcceptedJobTest {

    @Test
    fun `an accepted job defaults to queued when the server omits the status`() {
        val accepted = RppgAcceptedJob.parse(
            """{"jobId":"a","captureId":"b"}""",
        )
        assertEquals(RppgJobStatus.QUEUED.wireValue, accepted.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an accepted job without a jobId is rejected`() {
        RppgAcceptedJob.parse("""{"captureId":"b"}""")
    }
}

class RppgPollPlanTest {

    @Test
    fun `the foreground tick runs until the ceiling`() {
        assertEquals(RppgContract.POLL_INTERVAL_MS, RppgPollPlan.intervalMs(0L))
        assertEquals(
            RppgContract.POLL_INTERVAL_MS,
            RppgPollPlan.intervalMs(RppgContract.POLL_CEILING_MS - 1L),
        )
        assertFalse(RppgPollPlan.isSlow(RppgContract.POLL_CEILING_MS - 1L))
        assertNull(RppgPollPlan.notice(0L))
    }

    @Test
    fun `the ceiling drops polling to the background check and shows the notice`() {
        assertTrue(RppgPollPlan.isSlow(RppgContract.POLL_CEILING_MS))
        assertEquals(
            RppgContract.BACKGROUND_POLL_INTERVAL_MS,
            RppgPollPlan.intervalMs(RppgContract.POLL_CEILING_MS),
        )
        assertEquals(
            RppgContract.SLOW_ANALYSIS_NOTICE,
            RppgPollPlan.notice(RppgContract.POLL_CEILING_MS + 1L),
        )
    }

    @Test
    fun `authentication and permanent client errors stop polling`() {
        assertFalse(shouldRetryRppgPolling(AuthenticationRequiredException()))
        assertFalse(shouldRetryRppgPolling(ApiHttpException(404)))
    }

    @Test
    fun `transport and server failures remain retryable`() {
        assertTrue(shouldRetryRppgPolling(IllegalStateException("network")))
        assertTrue(shouldRetryRppgPolling(ApiHttpException(503)))
    }
}
