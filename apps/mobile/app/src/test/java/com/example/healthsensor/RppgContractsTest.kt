package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RppgContractsTest {
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
              "alertAction":"none"
            }""".trimIndent()
        )

        assertTrue(result.terminal)
        assertEquals(1, result.classIndex)
        assertEquals(72.4f, result.heartRateBpm!!, 0.001f)
        assertEquals("camera_rppg", org.json.JSONObject(result.toPrediction()!!.rawBody).getString("source"))
        assertEquals(AlertAction.NONE, AlertActionPolicy.resolve(result.toPrediction()!!))
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
