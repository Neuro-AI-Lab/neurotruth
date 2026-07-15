package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RppgFaceStabilityTrackerTest {
    @Test
    fun `one tracked face starts recording after continuous one second`() {
        val tracker = FaceStabilityTracker()

        assertEquals(FaceStabilityEvent.Progress(0), tracker.update(1_000, 1, 7, true))
        assertEquals(FaceStabilityEvent.Progress(600), tracker.update(1_600, 1, 7, true))
        assertTrue(tracker.update(2_000, 1, 7, true) is FaceStabilityEvent.StartRecording)
    }

    @Test
    fun `multiple faces or guide exit resets stabilization`() {
        val tracker = FaceStabilityTracker()

        tracker.update(1_000, 1, 7, true)
        assertEquals(FaceStabilityEvent.Progress(0), tracker.update(1_700, 2, null, false))
        assertEquals(FaceStabilityEvent.Progress(0), tracker.update(2_000, 1, 7, true))
        assertEquals(FaceStabilityEvent.Progress(900), tracker.update(2_900, 1, 7, true))
    }

    @Test
    fun `recording cancels only after one continuous second of invalid face`() {
        val tracker = FaceStabilityTracker()
        tracker.update(0, 1, 4, true)
        tracker.update(1_000, 1, 4, true)

        assertTrue(tracker.update(1_500, 0, null, false) is FaceStabilityEvent.KeepRecording)
        assertTrue(tracker.update(2_499, 2, null, false) is FaceStabilityEvent.KeepRecording)
        assertTrue(tracker.update(2_500, 2, null, false) is FaceStabilityEvent.CancelRecording)
    }
}
