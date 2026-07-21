package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AuthenticatedApiClientTest {
    @Test
    fun retriesOnceWithRotatedAccessTokenAfter401() {
        val requests = mutableListOf<ApiRequest>()
        val transport = ApiTransport { request ->
            requests += request
            when {
                request.url.endsWith("/api/auth/login") -> ApiResponse(
                    200,
                    authResponse("access-old", "refresh-old")
                )
                request.url.endsWith("/api/auth/refresh") -> {
                    assertTrue(request.body.orEmpty().contains("refresh-old"))
                    ApiResponse(200, authResponse("access-new", "refresh-new"))
                }
                request.headers["Authorization"] == "Bearer access-old" -> ApiResponse(401, "")
                request.headers["Authorization"] == "Bearer access-new" -> ApiResponse(200, "{\"ok\":true}")
                else -> error("unexpected request $request")
            }
        }
        val store = InMemoryAuthSessionStore()
        val client = AuthenticatedApiClient(ApiEndpoints("https://example.test"), store, transport)
        client.login(LoginRequest("patient@example.com", "password"))

        val result = client.executeAuthenticated(
            ApiRequest("POST", client.endpoints.sensorWindows, body = "{}")
        )

        assertEquals(200, result.statusCode)
        assertEquals("refresh-new", store.refreshToken)
        assertEquals(1, requests.count { it.url.endsWith("/api/auth/refresh") })
        assertEquals(2, requests.count { it.url.endsWith("/api/sensor-windows") })
    }

    @Test
    fun failedRefreshClearsStoredCredentialAndDoesNotRetryResource() {
        val requests = mutableListOf<ApiRequest>()
        val transport = ApiTransport { request ->
            requests += request
            when {
                request.url.endsWith("/api/auth/login") ->
                    ApiResponse(200, authResponse("access-old", "refresh-old"))
                request.url.endsWith("/api/auth/refresh") -> ApiResponse(401, "")
                else -> ApiResponse(401, "")
            }
        }
        val store = InMemoryAuthSessionStore()
        val client = AuthenticatedApiClient(ApiEndpoints("https://example.test"), store, transport)
        client.login(LoginRequest("patient@example.com", "password"))

        runCatching {
            client.executeAuthenticated(ApiRequest("GET", client.endpoints.me))
        }.onSuccess { error("request should fail") }

        assertNull(store.refreshToken)
        assertNull(client.currentAccessToken())
        assertEquals(1, requests.count { it.url.endsWith("/api/me") })
    }

    @Test
    fun endpointBuilderUsesV2AuthenticatedPathsAndUuidSessions() {
        val endpoints = ApiEndpoints("https://example.test/")
        val sessionId = UUID.randomUUID().toString()

        assertEquals("https://example.test/api/sensor-windows", endpoints.sensorWindows)
        assertEquals("https://example.test/api/predictions/stream", endpoints.predictionStream)
        assertEquals(
            "https://example.test/api/me/craving-probability-series?range=10m",
            endpoints.cravingProbabilitySeries("10m")
        )
        assertEquals(
            "https://example.test/api/me/craving-dashboard?timezone=Etc%2FGMT%2B5&eventRange=30d&auqRange=today",
            endpoints.cravingDashboard("Etc/GMT+5", "30d", "today")
        )
        assertEquals("https://example.test/api/sessions/$sessionId/messages", endpoints.messages(sessionId))
        assertEquals("https://example.test/api/sessions/$sessionId/finish", endpoints.finish(sessionId))
    }

    @Test
    fun streamingRefreshesOnceAfterUnauthorizedAndUsesRotatedBearer() {
        val transport = ApiTransport { request ->
            when {
                request.url.endsWith("/api/auth/login") ->
                    ApiResponse(200, authResponse("access-old", "refresh-old"))
                request.url.endsWith("/api/auth/refresh") ->
                    ApiResponse(200, authResponse("access-new", "refresh-new"))
                else -> error("unexpected transport request")
            }
        }
        val store = InMemoryAuthSessionStore()
        val client = AuthenticatedApiClient(ApiEndpoints("https://example.test"), store, transport)
        client.login(LoginRequest("patient@example.com", "password1234"))
        val seen = mutableListOf<String>()

        val result = client.executeAuthenticatedStream { token ->
            seen += token
            if (token == "access-old") throw HttpStatusException(401, "HTTP 401")
            "connected"
        }

        assertEquals("connected", result)
        assertEquals(listOf("access-old", "access-new"), seen)
        assertEquals("refresh-new", store.refreshToken)
    }

    @Test
    fun secondUnauthorizedAfterRefreshClearsSession() {
        val transport = ApiTransport { request ->
            when {
                request.url.endsWith("/api/auth/login") ->
                    ApiResponse(200, authResponse("access-old", "refresh-old"))
                request.url.endsWith("/api/auth/refresh") ->
                    ApiResponse(200, authResponse("access-new", "refresh-new"))
                else -> ApiResponse(401, "")
            }
        }
        val store = InMemoryAuthSessionStore()
        val client = AuthenticatedApiClient(ApiEndpoints("https://example.test"), store, transport)
        client.login(LoginRequest("patient@example.com", "password1234"))

        val failure = runCatching {
            client.executeAuthenticated(ApiRequest("GET", client.endpoints.me))
        }.exceptionOrNull()

        assertTrue(failure is AuthenticationRequiredException)
        assertNull(store.refreshToken)
        assertNull(client.currentAccessToken())
    }

    @Test
    fun consentUpdatesAppendExactImmutableSnapshotsAndParseReturnedState() {
        val requests = mutableListOf<ApiRequest>()
        val transport = ApiTransport { request ->
            requests += request
            when {
                request.url.endsWith("/api/auth/login") ->
                    ApiResponse(200, authResponse("access", "refresh"))
                request.url.endsWith("/api/me/consents") -> {
                    val posted = org.json.JSONObject(request.body!!)
                    ApiResponse(
                        201,
                        org.json.JSONObject(posted.toString())
                            .put("id", UUID.randomUUID().toString())
                            .put("voice", false)
                            .put("collectedAt", "2026-07-15T01:02:03Z")
                            .toString()
                    )
                }
                else -> error("unexpected request $request")
            }
        }
        val client = AuthenticatedApiClient(ApiEndpoints("https://example.test"), InMemoryAuthSessionStore(), transport)
        client.login(LoginRequest("patient@example.com", "password1234"))
        val initial = ConsentSelection(
            tos = true, privacy = true, sensitive = true,
            biosignal = true, aiAnalysis = true, notification = true, reportGeneration = true,
            tosVersion = "tos-v2", privacyVersion = "privacy-v2", consentFormVersion = "form-v2"
        )

        val first = client.appendConsent(initial)
        val second = client.appendConsent(initial.copy(biosignal = false, aiAnalysis = false))

        val consentRequests = requests.filter { it.url.endsWith("/api/me/consents") }
        assertEquals(2, consentRequests.size)
        consentRequests.forEach { request ->
            assertEquals("POST", request.method)
            assertEquals("Bearer access", request.headers["Authorization"])
            assertEquals(
                setOf(
                    "tos", "privacy", "sensitive", "biosignal", "aiAnalysis",
                    "cameraRppg", "faceVideoRetention", "voice", "notification", "reportGeneration",
                    "tosVersion", "privacyVersion", "consentFormVersion"
                ),
                org.json.JSONObject(request.body!!).keys().asSequence().toSet()
            )
        }
        assertTrue(org.json.JSONObject(consentRequests[0].body!!).getBoolean("biosignal"))
        assertFalse(org.json.JSONObject(consentRequests[1].body!!).getBoolean("biosignal"))
        assertTrue(first.reportGeneration)
        assertFalse(second.biosignal)
        assertFalse(second.aiAnalysis)
        assertEquals("form-v2", second.consentFormVersion)
    }

    private fun authResponse(access: String, refresh: String): String = """
        {
          "user": {
            "id": "patient-1",
            "email": "patient@example.com",
            "role": "patient",
            "status": "active",
            "mustChangePassword": false
          },
          "accessToken": "$access",
          "refreshToken": "$refresh",
          "expiresIn": 900
        }
    """.trimIndent()
}
