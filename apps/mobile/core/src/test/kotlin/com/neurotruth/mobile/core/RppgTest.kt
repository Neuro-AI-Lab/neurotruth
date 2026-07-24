package com.neurotruth.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RppgTest {

    private fun tracker() = FaceStabilityTracker()

    @Test
    fun `capture starts after one continuous second of a single tracked face`() {
        val tracker = tracker()
        assertEquals(
            FaceStabilityEvent.Progress(0L),
            tracker.update(0L, faceCount = 1, trackingId = 7, insideGuide = true),
        )
        assertEquals(
            FaceStabilityEvent.Progress(500L),
            tracker.update(500L, faceCount = 1, trackingId = 7, insideGuide = true),
        )
        assertEquals(
            FaceStabilityEvent.StartRecording,
            tracker.update(1_000L, faceCount = 1, trackingId = 7, insideGuide = true),
        )
    }

    @Test
    fun `a second face or a guide exit resets stabilization`() {
        val tracker = tracker()
        tracker.update(0L, 1, 7, insideGuide = true)
        assertEquals(
            FaceStabilityEvent.Progress(0L),
            tracker.update(600L, faceCount = 2, trackingId = 7, insideGuide = true),
        )

        val other = tracker()
        other.update(0L, 1, 7, insideGuide = true)
        assertEquals(
            FaceStabilityEvent.Progress(0L),
            other.update(600L, faceCount = 1, trackingId = 7, insideGuide = false),
        )
    }

    @Test
    fun `a changed tracking id restarts the clock`() {
        val tracker = tracker()
        tracker.update(0L, 1, 7, insideGuide = true)
        assertEquals(
            FaceStabilityEvent.Progress(0L),
            tracker.update(900L, faceCount = 1, trackingId = 8, insideGuide = true),
        )
    }

    @Test
    fun `a blink does not cancel a recording`() {
        val tracker = tracker()
        tracker.update(0L, 1, 7, insideGuide = true)
        tracker.update(1_000L, 1, 7, insideGuide = true)

        assertEquals(
            FaceStabilityEvent.KeepRecording,
            tracker.update(1_200L, faceCount = 0, trackingId = null, insideGuide = false),
        )
        assertEquals(
            FaceStabilityEvent.KeepRecording,
            tracker.update(1_400L, faceCount = 1, trackingId = 7, insideGuide = true),
        )
    }

    @Test
    fun `recording cancels after one continuous second of loss`() {
        val tracker = tracker()
        tracker.update(0L, 1, 7, insideGuide = true)
        tracker.update(1_000L, 1, 7, insideGuide = true)

        tracker.update(1_200L, faceCount = 0, trackingId = null, insideGuide = false)
        assertEquals(
            FaceStabilityEvent.CancelRecording,
            tracker.update(2_200L, faceCount = 0, trackingId = null, insideGuide = false),
        )
    }

    @Test
    fun `the capture duration contract is twenty seconds`() {
        assertEquals(20, RppgContract.CAPTURE_SECONDS)
        assertTrue(RppgContract.isValidDuration(20_000L))
        assertTrue(RppgContract.isValidDuration(19_500L))
        assertTrue(RppgContract.isValidDuration(20_500L))
        assertFalse(RppgContract.isValidDuration(19_499L))
        assertFalse(RppgContract.isValidDuration(20_501L))
    }

    @Test
    fun `the upload ceiling follows the server default of forty mebibytes`() {
        assertEquals(41_943_040L, RppgContract.MAX_UPLOAD_BYTES)
    }

    @Test
    fun `polling has an interval and a ceiling`() {
        assertEquals(2_000L, RppgContract.POLL_INTERVAL_MS)
        assertEquals(180_000L, RppgContract.POLL_CEILING_MS)
        assertEquals(30_000L, RppgContract.BACKGROUND_POLL_INTERVAL_MS)
    }

    @Test
    fun `only completed retry_required and failed are terminal`() {
        assertFalse(RppgJobStatus.QUEUED.isTerminal)
        assertFalse(RppgJobStatus.RUNNING.isTerminal)
        assertTrue(RppgJobStatus.COMPLETED.isTerminal)
        assertTrue(RppgJobStatus.RETRY_REQUIRED.isTerminal)
        assertTrue(RppgJobStatus.FAILED.isTerminal)
    }

    @Test
    fun `a quality failure needs a new capture while a transient one may retry the job`() {
        assertEquals(
            RppgNextStep.Recapture,
            RppgJobPolicy.next(RppgJobStatus.RETRY_REQUIRED, retryAllowed = true),
        )
        assertEquals(
            RppgNextStep.RetryJob,
            RppgJobPolicy.next(RppgJobStatus.FAILED, retryAllowed = true),
        )
        assertEquals(
            RppgNextStep.Abandon(null),
            RppgJobPolicy.next(RppgJobStatus.FAILED, retryAllowed = false),
        )
        assertEquals(
            RppgNextStep.RouteToChat,
            RppgJobPolicy.next(RppgJobStatus.COMPLETED, retryAllowed = false),
        )
    }

    @Test
    fun `a completed job routes to chat exactly once`() {
        val receipts = object : RppgRouteReceiptStore {
            private val routed = mutableSetOf<String>()
            override fun wasRouted(ownerUserId: String, jobId: String) = "$ownerUserId:$jobId" in routed
            override fun markRouted(ownerUserId: String, jobId: String) = routed.add("$ownerUserId:$jobId")
        }
        val routeOnce = RppgRouteOnce(receipts)

        assertTrue(routeOnce.claim("owner-1", "job-1"))
        assertFalse(routeOnce.claim("owner-1", "job-1"))
        assertTrue(routeOnce.claim("owner-1", "job-2"))
    }

    @Test
    fun `the receipt survives router recreation`() {
        val shared = object : RppgRouteReceiptStore {
            private val routed = mutableSetOf<String>()
            override fun wasRouted(ownerUserId: String, jobId: String) = "$ownerUserId:$jobId" in routed
            override fun markRouted(ownerUserId: String, jobId: String) = routed.add("$ownerUserId:$jobId")
        }
        assertTrue(RppgRouteOnce(shared).claim("owner-1", "job-1"))
        assertFalse(RppgRouteOnce(shared).claim("owner-1", "job-1"))
    }
}
