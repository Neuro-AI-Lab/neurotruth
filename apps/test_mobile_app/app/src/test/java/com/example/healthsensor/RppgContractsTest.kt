package com.example.healthsensor

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeWatchNodeSource : WatchNodeSource {
    private var onSuccess: ((Int) -> Unit)? = null
    private var onFailure: ((Throwable) -> Unit)? = null
    var queryCount = 0
        private set

    override fun query(onSuccess: (Int) -> Unit, onFailure: (Throwable) -> Unit) {
        queryCount += 1
        this.onSuccess = onSuccess
        this.onFailure = onFailure
    }

    fun succeed(count: Int) = onSuccess!!.invoke(count)
    fun fail() = onFailure!!.invoke(IllegalStateException("query failed"))
}

private class FakeWatchPeerEvents {
    private var listener: (() -> Unit)? = null

    fun subscribe(listener: () -> Unit): Closeable {
        this.listener = listener
        return Closeable {
            this.listener = null
        }
    }

    fun publish() = listener?.invoke()
}

class RppgContractsTest {
    @Test
    fun `camera countdown matches the 20 second recording contract`() {
        assertEquals(20, RPPG_CAPTURE_SECONDS)
    }

    @Test
    fun `zero connected nodes is the only disconnected result`() {
        val nodes = FakeWatchNodeSource()
        val peers = FakeWatchPeerEvents()
        var disconnects = 0
        val monitor = WatchConnectionMonitor(nodes, peers::subscribe) { disconnects += 1 }

        assertEquals(WatchConnectionState.CHECKING, monitor.state.value)
        nodes.succeed(0)

        assertEquals(WatchConnectionState.DISCONNECTED, monitor.state.value)
        assertEquals(1, disconnects)
    }

    @Test
    fun `one or more connected nodes is connected`() {
        val nodes = FakeWatchNodeSource()
        val monitor = WatchConnectionMonitor(nodes, FakeWatchPeerEvents()::subscribe)

        nodes.succeed(2)

        assertEquals(WatchConnectionState.CONNECTED, monitor.state.value)
    }

    @Test
    fun `connected node query failure is error rather than disconnected`() {
        val nodes = FakeWatchNodeSource()
        val monitor = WatchConnectionMonitor(nodes, FakeWatchPeerEvents()::subscribe)

        nodes.fail()

        assertEquals(WatchConnectionState.ERROR, monitor.state.value)
    }

    @Test
    fun `peer event refreshes the authoritative connected node query`() {
        val nodes = FakeWatchNodeSource()
        val peers = FakeWatchPeerEvents()
        val monitor = WatchConnectionMonitor(nodes, peers::subscribe)
        nodes.succeed(1)

        peers.publish()

        assertEquals(2, nodes.queryCount)
        assertEquals(WatchConnectionState.CHECKING, monitor.state.value)
        nodes.succeed(0)
        assertEquals(WatchConnectionState.DISCONNECTED, monitor.state.value)
    }

    @Test
    fun `monitor close removes the app local peer subscription`() {
        val nodes = FakeWatchNodeSource()
        val monitor = WatchConnectionMonitor(nodes, WatchPeerConnectionEvents::subscribe)

        monitor.close()
        WatchPeerConnectionEvents.publish()

        assertEquals(1, nodes.queryCount)
    }

    @Test
    fun `watch state and rppg gates choose safe CTA presentation`() {
        val ready = RppgServiceStatus(true, true, true)
        val disconnected = WatchRppgPresentationPolicy.resolve(
            WatchConnectionState.DISCONNECTED,
            canCaptureRppg = true,
            serviceStatus = ready
        )
        val connected = WatchRppgPresentationPolicy.resolve(
            WatchConnectionState.CONNECTED,
            canCaptureRppg = true,
            serviceStatus = ready
        )

        assertTrue(disconnected.primaryAction)
        assertTrue(disconnected.actionEnabled)
        assertEquals("20초 얼굴 측정 시작", disconnected.actionLabel)
        assertEquals("연결 안 됨", disconnected.watchLabel)
        assertFalse(connected.primaryAction)
        assertFalse(connected.actionEnabled)
        assertEquals("Watch 연결 중 측정 불가", connected.actionLabel)
        val checking = WatchRppgPresentationPolicy.resolve(
                WatchConnectionState.CHECKING,
                true,
                ready
            )
        val error = WatchRppgPresentationPolicy.resolve(
                WatchConnectionState.ERROR,
                true,
                ready
            )
        assertFalse(checking.primaryAction)
        assertFalse(checking.actionEnabled)
        assertFalse(error.primaryAction)
        assertFalse(error.actionEnabled)

        val consentRequired = WatchRppgPresentationPolicy.resolve(
            WatchConnectionState.DISCONNECTED,
            false,
            ready
        )
        assertFalse(consentRequired.actionEnabled)
        assertEquals("동의 후 얼굴 측정", consentRequired.actionLabel)

        listOf(
            WatchRppgPresentationPolicy.resolve(
                WatchConnectionState.DISCONNECTED,
                true,
                null
            ),
            WatchRppgPresentationPolicy.resolve(
                WatchConnectionState.DISCONNECTED,
                true,
                RppgServiceStatus(false, false, false)
            ),
            WatchRppgPresentationPolicy.resolve(
                WatchConnectionState.DISCONNECTED,
                true,
                RppgServiceStatus(true, false, false)
            ),
            WatchRppgPresentationPolicy.resolve(
                WatchConnectionState.DISCONNECTED,
                true,
                RppgServiceStatus(true, true, false)
            )
        ).forEach { presentation ->
            assertTrue(presentation.primaryAction)
            assertFalse(presentation.actionEnabled)
            assertEquals("상태 다시 확인", presentation.actionLabel)
            assertTrue(presentation.guidance.isNotBlank())
        }
    }

    @Test
    fun `API endpoints point only at NeuroTruth backend`() {
        val endpoints = ApiEndpoints("https://neurotruth.internal")

        assertEquals("https://neurotruth.internal/api/rppg/status", endpoints.rppgStatus)
        assertEquals("https://neurotruth.internal/api/rppg/jobs", endpoints.rppgJobs)
        assertEquals(
            "https://neurotruth.internal/api/rppg/jobs/11111111-1111-1111-1111-111111111111/retry",
            endpoints.retryRppgJob("11111111-1111-1111-1111-111111111111")
        )
        assertFalse(endpoints.rppgStatus.contains("192.168.68.50"))
    }

    @Test
    fun `completed job parser preserves camera card and prediction fields`() {
        val result = RppgJobResult.parse(
            """{
              "jobId":"11111111-1111-1111-1111-111111111111",
              "captureId":"22222222-2222-2222-2222-222222222222",
              "status":"completed",
              "capturedAtMs":123456,
              "classIndex":1,
              "confidence":0.91,
              "heartRateBpm":72.4,
              "qualityScore":0.88,
              "processingMs":4200,
              "rppgSampleCount":1024,
              "rppgSamplingHz":51.2,
              "alertAction":"none"
            }""".trimIndent()
        )

        assertTrue(result.terminal)
        assertEquals(1, result.classIndex)
        assertEquals(72.4f, result.heartRateBpm!!, 0.001f)
        assertEquals(1024, result.rppgSampleCount)
        assertEquals(51.2f, result.rppgSamplingHz!!, 0.001f)
        assertEquals(0.91f, result.toPrediction()!!.cravingProbability!!, 0.001f)
        assertEquals("camera_rppg", result.toPrediction()!!.source)
        assertEquals("camera_rppg", org.json.JSONObject(result.toPrediction()!!.rawBody).getString("source"))
        assertEquals(AlertAction.NONE, AlertActionPolicy.resolve(result.toPrediction()!!))
    }

    @Test
    fun `delayed camera result does not replace a newer watch prediction`() {
        PhoneMonitoringState.startNewSession(startedAtMs = 100L, sessionId = "latest-source")
        try {
            val watch = CravingPrediction(
                cravingClass = 0,
                timestampMs = 2_000L,
                rawBody = "watch",
                source = "watch_sensor"
            )
            val camera = CravingPrediction(
                cravingClass = 1,
                timestampMs = 1_000L,
                rawBody = "camera",
                source = "camera_rppg"
            )

            assertTrue(PhoneMonitoringState.publishPrediction(watch))
            assertFalse(PhoneMonitoringState.publishPrediction(camera))
            assertEquals(watch, PhoneMonitoringState.latestPrediction.value)
        } finally {
            PhoneMonitoringState.startNewSession()
        }
    }

    @Test
    fun `quality retry has no craving prediction`() {
        val result = RppgJobResult.parse(
            """{
              "jobId":"11111111-1111-1111-1111-111111111111",
              "captureId":"22222222-2222-2222-2222-222222222222",
              "status":"retry_required",
              "capturedAtMs":123456,
              "failureCode":"RPPG_QUALITY_RECAPTURE",
              "retryAllowed":false
            }""".trimIndent()
        )

        assertNull(result.toPrediction())
        assertFalse(result.retryAllowed)
    }

    @Test
    fun `new consent fields round trip and gate camera independently`() {
        val consent = ConsentSelection(
            tos = true,
            privacy = true,
            sensitive = true,
            biosignal = true,
            aiAnalysis = true,
            cameraRppg = true,
            faceVideoRetention = true,
            notification = false,
            reportGeneration = false,
            tosVersion = "v2.5",
            privacyVersion = "v2.5",
            consentFormVersion = "v2.5"
        )
        val restored = ConsentSelection.fromJson(consent.toJson())!!
        val auth = MobileAuthState(
            AuthUser("patient", "p@example.com", "patient", "active", false),
            restored
        )

        assertTrue(restored.cameraRppg)
        assertTrue(restored.faceVideoRetention)
        assertTrue(auth.canCaptureRppg)
        assertFalse(auth.canNotify)
    }

    @Test
    fun `pause recovery restores only prior consent-eligible states`() {
        val original = RppgMonitoringSnapshot(uploadEnabled = true, sseEnabled = false)

        assertEquals(original, RppgPauseRecoveryPolicy.restore(original, true, true))
        assertEquals(
            RppgMonitoringSnapshot(false, false),
            RppgPauseRecoveryPolicy.restore(original, canUpload = false, canReceivePredictions = false)
        )
    }

    @Test
    fun `camera predictions are phone only regardless of notification consent`() {
        assertFalse(RppgPredictionRoutingPolicy.relayToWatch)
        assertTrue(RppgPredictionRoutingPolicy.allowPhonePresentation(true))
        assertFalse(RppgPredictionRoutingPolicy.allowPhonePresentation(false))
    }

    @Test
    fun `backend camera alert action suppresses class based escalation exactly`() {
        listOf("none", "warming_up", "cooldown").forEach { backendAction ->
            val result = RppgJobResult.parse(
                """{
                  "jobId":"11111111-1111-1111-1111-111111111111",
                  "captureId":"22222222-2222-2222-2222-222222222222",
                  "status":"completed",
                  "capturedAtMs":123456,
                  "classIndex":1,
                  "alertAction":"$backendAction"
                }""".trimIndent()
            )
            val resolved = AlertActionPolicy.resolve(result.toPrediction()!!)
            if (backendAction == "cooldown") {
                assertEquals(AlertAction.COOLDOWN, resolved)
            } else {
                assertEquals(AlertAction.NONE, resolved)
            }
        }
    }

    @Test
    fun `completed result routing receipt survives router recreation`() {
        val receipts = mutableSetOf<Pair<String, String>>()
        val persistentStore = object : RppgRouteReceiptStore {
            override fun wasRouted(ownerUserId: String, jobId: String): Boolean =
                ownerUserId to jobId in receipts

            override fun markRouted(ownerUserId: String, jobId: String): Boolean =
                receipts.add(ownerUserId to jobId)
        }

        assertTrue(RppgRouteOnce(persistentStore).claim("patient-1", "job-1"))
        assertFalse(RppgRouteOnce(persistentStore).claim("patient-1", "job-1"))
        assertTrue(RppgRouteOnce(persistentStore).claim("patient-1", "job-2"))
    }
}
