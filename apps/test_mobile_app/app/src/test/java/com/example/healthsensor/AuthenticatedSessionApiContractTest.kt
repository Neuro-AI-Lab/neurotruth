package com.example.healthsensor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedSessionApiContractTest {
    private val sessionId = "1b38d6d4-b27f-4d61-bf7d-4579ca4a4bdf"
    private val clientMessageId = "6b38d6d4-b27f-4d61-bf7d-4579ca4a4bdf"

    @Test
    fun usesSlotFreeSessionAndStatusOnlyReportContracts() {
        val requests = mutableListOf<ApiRequest>()
        val transport = ApiTransport { request ->
            requests += request
            when {
                request.url.endsWith("/api/auth/login") -> ApiResponse(200, authResponse())
                request.url.endsWith("/api/sessions") -> ApiResponse(200, sessionJson("in_progress", "free_dialogue"))
                request.url.endsWith("/messages") -> ApiResponse(200, messageJson())
                request.url.endsWith("/assessments") -> ApiResponse(200, "{}")
                request.url.endsWith("/finish") -> ApiResponse(200, sessionJson("completed", "completed"))
                request.method == "GET" && request.url.endsWith("/reports") -> ApiResponse(
                    200,
                    """[{"reportId":"22222222-2222-4222-8222-222222222222","version":1,"status":"ready","createdAt":"2026-07-15T01:02:03Z","generatedAt":"2026-07-15T01:03:03Z","body":"must not be parsed"}]"""
                )
                else -> error("unexpected request: $request")
            }
        }
        val client = AuthenticatedApiClient(ApiEndpoints("https://example.test"), InMemoryAuthSessionStore(), transport)
        client.login(LoginRequest("patient@example.com", "password1234"))
        val api = AuthenticatedSessionApi(client)

        val created = api.create("alert_checkin", "33333333-3333-4333-8333-333333333333")
        val message = api.postMessage(sessionId, clientMessageId, "지금 불안해요", 3_600_000, "voice")
        api.postAssessment(
            sessionId,
            StateCheckResult(100L, 1, List(8) { 6 }, List(8) { 6 }, 48, 6f, 48, 6f)
        )
        val finished = api.finish(sessionId)
        val report = api.getReport(sessionId)

        val createBody = JSONObject(requests.single { it.url.endsWith("/api/sessions") }.body!!)
        assertEquals(setOf("sessionType", "triggerAlertId"), createBody.keys().asSequence().toSet())
        assertEquals("alert_checkin", createBody.getString("sessionType"))
        assertEquals("33333333-3333-4333-8333-333333333333", createBody.getString("triggerAlertId"))
        val messageBody = JSONObject(requests.single { it.url.endsWith("/messages") }.body!!)
        assertEquals(setOf("clientMessageId", "content", "inputModality"), messageBody.keys().asSequence().toSet())
        assertEquals(clientMessageId, messageBody.getString("clientMessageId"))
        assertEquals("voice", messageBody.getString("inputModality"))
        val assessmentBody = JSONObject(requests.single { it.url.endsWith("/assessments") }.body!!)
        assertEquals("2.0", assessmentBody.getString("version"))
        assertEquals(0.0, assessmentBody.getDouble("scaleMin"), 0.0)
        assertEquals(48.0, assessmentBody.getDouble("scaleMax"), 0.0)
        assertEquals(48.0, assessmentBody.getDouble("rawScore"), 0.0)
        assertEquals("https://example.test/api/sessions/$sessionId/transcriptions", client.endpoints.transcriptions(sessionId))

        assertEquals("free_dialogue", created.interactionPhase)
        assertEquals(4_200, created.inactivityTimeoutSeconds)
        assertEquals("free_dialogue", message.phase)
        assertEquals(4_200, message.inactivityTimeoutSeconds)
        assertEquals("호흡을 천천히 해보세요", message.activeInterventions.single().content)
        assertEquals("completed", finished.status)
        assertTrue(report.completed)
        assertEquals(1, report.version)
        assertNull(report.generatedAtMs?.takeIf { it <= 0L })
        assertFalse(requests.any { it.body?.contains("missingSlots") == true || it.body?.contains("handoffReady") == true })
    }

    @Test
    fun structuredProviderFailureExposesSingleRetryMetadata() {
        val transport = ApiTransport { request ->
            when {
                request.url.endsWith("/api/auth/login") -> ApiResponse(200, authResponse())
                request.url.endsWith("/messages") -> ApiResponse(
                    502,
                    """{"detail":{"code":"dialogue_provider_error","clientMessageId":"$clientMessageId","userMessageId":"77777777-7777-4777-8777-777777777777","retryable":true,"attemptsRemaining":1}}"""
                )
                else -> error("unexpected request: $request")
            }
        }
        val client = AuthenticatedApiClient(ApiEndpoints("https://example.test"), InMemoryAuthSessionStore(), transport)
        client.login(LoginRequest("patient@example.com", "password1234"))

        val error = runCatching {
            AuthenticatedSessionApi(client).postMessage(
                sessionId,
                clientMessageId,
                "같은 메시지",
                3_600_000,
                "text"
            )
        }.exceptionOrNull() as DialogueRequestException

        assertEquals("dialogue_provider_error", error.code)
        assertEquals(clientMessageId, error.clientMessageId)
        assertTrue(error.retryable)
        assertEquals(1, error.attemptsRemaining)
    }

    @Test
    fun parsersExposeAbandonedAndNeverRequireLegacySlots() {
        val abandoned = SessionResponseParser.parseSession(sessionJson("abandoned", "abandoned"))
        val response = SessionResponseParser.parseMessage(messageJson(phase = "closing"))

        assertTrue(abandoned.terminal)
        assertEquals("closing", response.phase)
        assertEquals("mid", response.stateSnapshot?.state)
    }

    private fun sessionJson(status: String, phase: String) = """{
      "sessionId":"$sessionId","status":"$status","interactionPhase":"$phase",
      "assistantText":"무엇이 가장 필요한가요?","activeInterventions":[],"reportStatus":"not_started",
      "legacy":false,"inactivityTimeoutSeconds":4200,"startedAt":"2026-07-15T01:02:03Z","updatedAt":"2026-07-15T01:02:04Z"
    }"""

    private fun messageJson(phase: String = "free_dialogue") = """{
      "userMessageId":"77777777-7777-4777-8777-777777777777",
      "assistantMessageId":"88888888-8888-4888-8888-888888888888",
      "assistantText":"함께 해볼까요?","phase":"$phase","reportStatus":"pending","inactivityTimeoutSeconds":4200,
      "safety":{"status":"clear","riskCodes":[],"supportResources":[]},
      "activeInterventions":[{"id":"44444444-4444-4444-8444-444444444444","type":"breathing","status":"delivered","presentationOrder":1,"content":"호흡을 천천히 해보세요","createdAt":"2026-07-15T01:02:05Z"}],
      "stateSnapshot":{"inferenceId":"55555555-5555-4555-8555-555555555555","scope":"realtime","state":"mid","confidence":0.7,"summaryStatus":"ready","summary":"긴장","createdAt":"2026-07-15T01:02:05Z"}
    }"""

    private fun authResponse() = """{
      "user":{"id":"patient-1","email":"patient@example.com","role":"patient","status":"active","mustChangePassword":false},
      "accessToken":"access-token","refreshToken":"refresh-token","expiresIn":900
    }"""
}
