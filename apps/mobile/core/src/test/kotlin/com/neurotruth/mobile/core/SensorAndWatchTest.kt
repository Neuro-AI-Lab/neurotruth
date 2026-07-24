package com.neurotruth.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorAndWatchTest {

    private fun gates(cameraReady: Boolean): ConsentGates = ConsentGates(
        ConsentSelection(
            tos = true, privacy = true, sensitive = true,
            biosignal = true, aiAnalysis = true, notification = true, reportGeneration = true,
            cameraRppg = cameraReady, faceVideoRetention = cameraReady,
        ),
    )

    @Test
    fun `the window contract is twenty seconds on a ten second cadence`() {
        assertEquals(20_000L, SensorWindowContract.WINDOW_MS)
        assertEquals(10_000L, SensorWindowContract.UPLOAD_INTERVAL_MS)
        assertEquals(20_000L, SensorWindowContract.WARM_UP_MS)
        assertEquals(500, SensorWindowContract.PPG_SAMPLES_PER_CHANNEL)
        assertEquals(20, SensorWindowContract.EDA_SAMPLES)
    }

    @Test
    fun `window timing outside the tolerance is rejected`() {
        assertTrue(SensorWindowContract.isValidWindow(0L, 20_000L))
        assertTrue(SensorWindowContract.isValidWindow(0L, 19_500L))
        assertTrue(SensorWindowContract.isValidWindow(0L, 20_500L))
        assertFalse(SensorWindowContract.isValidWindow(0L, 19_499L))
        assertFalse(SensorWindowContract.isValidWindow(0L, 20_501L))
        assertFalse(SensorWindowContract.isValidWindow(20_000L, 20_000L))
        assertFalse(SensorWindowContract.isValidWindow(20_000L, 0L))
    }

    @Test
    fun `upload failures route by status`() {
        assertEquals(UploadFailureAction.AUTHENTICATION_REQUIRED, UploadFailurePolicy.resolve(401))
        assertEquals(UploadFailureAction.DROP_AND_CONTINUE, UploadFailurePolicy.resolve(409))
        assertEquals(UploadFailureAction.RETRY_SAME_PAYLOAD, UploadFailurePolicy.resolve(429))
        assertEquals(UploadFailureAction.PAUSE_AND_DROP, UploadFailurePolicy.resolve(403))
        assertEquals(UploadFailureAction.PAUSE_AND_DROP, UploadFailurePolicy.resolve(422))
        assertEquals(UploadFailureAction.RETRY_SAME_PAYLOAD, UploadFailurePolicy.resolve(503))
        assertEquals(UploadFailureAction.RETRY_SAME_PAYLOAD, UploadFailurePolicy.resolve(-1))
    }

    @Test
    fun `the client window key is stable for a session and sequence`() {
        assertEquals("window:session-a:7", ClientWindowKey.of("session-a", 7L))
        assertEquals(ClientWindowKey.of("session-a", 7L), ClientWindowKey.of("session-a", 7L))
    }

    @Test
    fun `the offline queue evicts oldest beyond its cap`() {
        val queue = OfflineWindowQueue<Int>(capacity = 3)
        assertTrue(queue.offer(1))
        assertTrue(queue.offer(2))
        assertTrue(queue.offer(3))
        assertFalse(queue.offer(4))

        assertEquals(3, queue.size())
        assertEquals(1L, queue.dropped())
        assertEquals(2, queue.poll())
        assertEquals(3, queue.poll())
        assertEquals(4, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `the default offline cap is ten minutes of windows`() {
        assertEquals(60, OfflineWindowQueue.DEFAULT_CAPACITY)
    }

    @Test
    fun `disconnection is only confirmed after two empty queries three seconds apart`() {
        val tracker = WatchConnectionTracker()
        assertEquals(WatchConnectionState.CHECKING, tracker.onNodesQueried(0L, connectedNodeCount = 0))
        assertEquals(WatchConnectionState.CHECKING, tracker.onNodesQueried(2_999L, connectedNodeCount = 0))
        assertEquals(WatchConnectionState.DISCONNECTED, tracker.onNodesQueried(3_000L, connectedNodeCount = 0))
    }

    @Test
    fun `a single connected node is connected immediately`() {
        val tracker = WatchConnectionTracker()
        assertEquals(WatchConnectionState.CONNECTED, tracker.onNodesQueried(0L, connectedNodeCount = 1))
    }

    @Test
    fun `a failed query is an error and never a disconnection`() {
        val tracker = WatchConnectionTracker()
        tracker.onNodesQueried(0L, connectedNodeCount = 0)
        assertEquals(WatchConnectionState.ERROR, tracker.onQueryFailed())
        assertFalse(tracker.current().isConfirmedDisconnected)

        // The confirmation window restarts rather than carrying over the earlier empty observation.
        assertEquals(WatchConnectionState.CHECKING, tracker.onNodesQueried(10_000L, connectedNodeCount = 0))
    }

    @Test
    fun `a peer event only triggers a re-query`() {
        val tracker = WatchConnectionTracker()
        tracker.onNodesQueried(0L, connectedNodeCount = 1)
        assertEquals(WatchConnectionState.CHECKING, tracker.onPeerEvent())
    }

    @Test
    fun `the camera action is disabled while a watch is connected`() {
        val blocked = CameraActionPolicy.resolve(
            WatchConnectionState.CONNECTED,
            gates(cameraReady = true),
            cameraPermissionGranted = true,
            rppgServiceReady = true,
        )
        assertEquals(
            CameraAvailability.Blocked(CameraActionPolicy.REASON_WATCH_CONNECTED),
            blocked,
        )
    }

    @Test
    fun `the camera action stays disabled while the watch state is unknown`() {
        listOf(WatchConnectionState.CHECKING, WatchConnectionState.ERROR).forEach { state ->
            assertEquals(
                CameraAvailability.Blocked(CameraActionPolicy.REASON_WATCH_UNKNOWN),
                CameraActionPolicy.resolve(state, gates(true), true, true),
            )
        }
    }

    @Test
    fun `the camera action needs consent permission and a ready service`() {
        assertEquals(
            CameraAvailability.Blocked(CameraActionPolicy.REASON_CONSENT),
            CameraActionPolicy.resolve(WatchConnectionState.DISCONNECTED, gates(false), true, true),
        )
        assertEquals(
            CameraAvailability.Blocked(CameraActionPolicy.REASON_PERMISSION),
            CameraActionPolicy.resolve(WatchConnectionState.DISCONNECTED, gates(true), false, true),
        )
        assertEquals(
            CameraAvailability.Blocked(CameraActionPolicy.REASON_SERVICE),
            CameraActionPolicy.resolve(WatchConnectionState.DISCONNECTED, gates(true), true, false),
        )
        assertEquals(
            CameraAvailability.Available,
            CameraActionPolicy.resolve(WatchConnectionState.DISCONNECTED, gates(true), true, true),
        )
    }
}
