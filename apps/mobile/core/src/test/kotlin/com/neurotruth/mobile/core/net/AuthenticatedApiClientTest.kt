package com.neurotruth.mobile.core.net

import com.neurotruth.mobile.core.ConsentSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun tokenBody(accessToken: String, refreshToken: String): String = """
    {"user":{"id":"u-1","email":"patient@example.com","role":"patient","status":"active"},
     "accessToken":"$accessToken","refreshToken":"$refreshToken","expiresIn":900}
""".trimIndent()

/**
 * Answers refresh calls with a fresh token pair each time and delegates everything else, so a test
 * can assert how many times the client refreshed and which bearer it replayed with.
 */
private class FakeBackend(
    private val refreshEndpoint: String,
    private val resource: (ApiRequest) -> ApiResponse,
) : ApiTransport {
    val requests = mutableListOf<ApiRequest>()
    var refreshCount = 0
        private set

    override fun execute(request: ApiRequest): ApiResponse {
        requests.add(request)
        if (request.url == refreshEndpoint) {
            refreshCount += 1
            return ApiResponse(200, tokenBody("access-$refreshCount", "refresh-$refreshCount"))
        }
        return resource(request)
    }

    fun requestsTo(url: String): List<ApiRequest> = requests.filter { it.url == url }
}

class AuthenticatedApiClientTest {

    private val endpoints = ApiEndpoints("http://localhost:58441")

    private fun bearerOf(request: ApiRequest): String? =
        request.headers["Authorization"]?.removePrefix("Bearer ")

    private fun signedInClient(
        transport: ApiTransport,
        store: RefreshTokenStore,
    ): AuthenticatedApiClient =
        AuthenticatedApiClient(endpoints, transport, store).apply { recoverSession() }

    @Test
    fun `a 401 triggers exactly one refresh and one replay with the rotated token`() {
        val backend = FakeBackend(endpoints.refresh) { request ->
            if (bearerOf(request) == "access-1") ApiResponse(401, "") else ApiResponse(200, """{"ok":true}""")
        }
        val store = InMemoryRefreshTokenStore("refresh-0")
        val client = signedInClient(backend, store)
        assertEquals(1, backend.refreshCount)

        val response = client.execute(ApiRequest("GET", endpoints.me))

        assertEquals(200, response.statusCode)
        assertEquals(2, backend.refreshCount)
        assertEquals(2, backend.requestsTo(endpoints.me).size)
        assertEquals("access-1", bearerOf(backend.requestsTo(endpoints.me).first()))
        assertEquals("access-2", bearerOf(backend.requestsTo(endpoints.me).last()))
        assertEquals("refresh-2", store.loadRefreshToken())
    }

    @Test
    fun `a second 401 after refresh clears the session and stops`() {
        val backend = FakeBackend(endpoints.refresh) { ApiResponse(401, "") }
        val store = InMemoryRefreshTokenStore("refresh-0")
        val client = signedInClient(backend, store)

        assertThrows(AuthenticationRequiredException::class.java) {
            client.execute(ApiRequest("GET", endpoints.me))
        }
        // The original attempt and exactly one replay. There is never a third.
        assertEquals(2, backend.requestsTo(endpoints.me).size)
        assertNull(store.loadRefreshToken())
    }

    @Test
    fun `a wrong current password does not sign the user out`() {
        // change-password answers 401 for a wrong current password. The refresh still runs, so an
        // expired token is recovered; a 401 that survives it is the endpoint's answer, not a
        // dead session.
        val backend = FakeBackend(endpoints.refresh) { ApiResponse(401, """{"code":"invalid_password"}""") }
        val store = InMemoryRefreshTokenStore("refresh-0")
        val client = signedInClient(backend, store)

        val response = client.execute(
            ApiRequest(
                "POST",
                endpoints.changePassword,
                body = "{}",
                treatUnauthorizedAsResponse = true,
            ),
        )

        assertEquals(401, response.statusCode)
        assertEquals("refresh-2", store.loadRefreshToken())
        assertEquals(2, backend.requestsTo(endpoints.changePassword).size)
    }

    @Test
    fun `an expired token on that same route still recovers`() {
        val backend = FakeBackend(endpoints.refresh) { request ->
            if (bearerOf(request) == "access-1") ApiResponse(401, "") else ApiResponse(204, "")
        }
        val client = signedInClient(backend, InMemoryRefreshTokenStore("refresh-0"))

        val response = client.execute(
            ApiRequest(
                "POST",
                endpoints.changePassword,
                body = "{}",
                treatUnauthorizedAsResponse = true,
            ),
        )
        assertEquals(204, response.statusCode)
    }

    @Test
    fun `a failed refresh clears the credential and does not replay`() {
        val requests = mutableListOf<ApiRequest>()
        val transport = ApiTransport { request ->
            requests.add(request)
            ApiResponse(401, "")
        }
        val store = InMemoryRefreshTokenStore("refresh-0")
        val client = AuthenticatedApiClient(endpoints, transport, store)

        assertThrows(AuthenticationRequiredException::class.java) {
            client.execute(ApiRequest("GET", endpoints.me))
        }
        assertNull(store.loadRefreshToken())
        assertTrue(requests.none { it.url == endpoints.me })
    }

    @Test
    fun `without any credential the call fails before touching the network`() {
        val requests = mutableListOf<ApiRequest>()
        val transport = ApiTransport { request ->
            requests.add(request)
            ApiResponse(200, "{}")
        }
        val client = AuthenticatedApiClient(endpoints, transport, InMemoryRefreshTokenStore(null))

        assertThrows(AuthenticationRequiredException::class.java) {
            client.execute(ApiRequest("GET", endpoints.me))
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a successful call is not retried`() {
        val backend = FakeBackend(endpoints.refresh) { ApiResponse(200, """{"ok":true}""") }
        val client = signedInClient(backend, InMemoryRefreshTokenStore("refresh-0"))

        client.execute(ApiRequest("GET", endpoints.me))

        assertEquals(1, backend.requestsTo(endpoints.me).size)
        assertEquals(1, backend.refreshCount)
    }

    @Test
    fun `the streaming variant refreshes once and replays`() {
        val backend = FakeBackend(endpoints.refresh) { ApiResponse(200, "{}") }
        val client = signedInClient(backend, InMemoryRefreshTokenStore("refresh-0"))

        var attempts = 0
        val usedToken = client.executeStreaming { token ->
            attempts += 1
            if (attempts == 1) throw HttpStatusException(401, "HTTP 401")
            token
        }

        assertEquals(2, attempts)
        assertEquals("access-2", usedToken)
    }

    @Test
    fun `a non-401 streaming error is not retried`() {
        val backend = FakeBackend(endpoints.refresh) { ApiResponse(200, "{}") }
        val client = signedInClient(backend, InMemoryRefreshTokenStore("refresh-0"))

        var attempts = 0
        assertThrows(HttpStatusException::class.java) {
            client.executeStreaming<String> {
                attempts += 1
                throw HttpStatusException(503, "HTTP 503")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `logout retains the session when the server rejects it`() {
        val backend = FakeBackend(endpoints.refresh) { ApiResponse(503, """{"code":"demo_delete_failed"}""") }
        val store = InMemoryRefreshTokenStore("refresh-0")
        val client = signedInClient(backend, store)

        assertThrows(ApiHttpException::class.java) { client.logout() }

        assertEquals("refresh-1", store.loadRefreshToken())
        assertEquals("patient@example.com", client.user()?.email)
    }

    @Test
    fun `logout retains the session on a transport failure`() {
        val backend = FakeBackend(endpoints.refresh) { throw IllegalStateException("offline") }
        val store = InMemoryRefreshTokenStore("refresh-0")
        val client = signedInClient(backend, store)

        assertThrows(IllegalStateException::class.java) { client.logout() }

        assertEquals("refresh-1", store.loadRefreshToken())
        assertTrue(client.hasSession())
    }

    @Test
    fun `logout clears the session only after server success`() {
        val backend = FakeBackend(endpoints.refresh) { ApiResponse(204, "") }
        val store = InMemoryRefreshTokenStore("refresh-0")
        val client = signedInClient(backend, store)

        client.logout()

        assertNull(store.loadRefreshToken())
        assertNull(client.user())
    }

    @Test
    fun `signup carries the consent snapshot and adopts the returned session`() {
        val requests = mutableListOf<ApiRequest>()
        val transport = ApiTransport { request ->
            requests.add(request)
            ApiResponse(200, tokenBody("access-1", "refresh-1"))
        }
        val store = InMemoryRefreshTokenStore(null)
        val client = AuthenticatedApiClient(endpoints, transport, store)

        val consent = ConsentSelection(
            tos = true, privacy = true, sensitive = true,
            biosignal = true, aiAnalysis = true, notification = true, reportGeneration = true,
        )
        val tokens = client.signup(
            PatientSignupRequest("patient@example.com", "at-least-12-characters", consent, name = "홍길동"),
        )

        assertEquals("patient@example.com", tokens.user.email)
        assertEquals("refresh-1", store.loadRefreshToken())

        val body = requests.first().body.orEmpty()
        assertTrue(body.contains("\"consentFormVersion\""))
        assertTrue(body.contains("\"tosVersion\""))
        assertTrue(body.contains("\"name\""))
    }

    @Test
    fun `a short password never reaches the network`() {
        val consent = ConsentSelection(
            tos = true, privacy = true, sensitive = true,
            biosignal = true, aiAnalysis = true, notification = true, reportGeneration = true,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PatientSignupRequest("patient@example.com", "short", consent)
        }
    }

    @Test
    fun `a json content type is added only when a body is present`() {
        val backend = FakeBackend(endpoints.refresh) { ApiResponse(200, """{"ok":true}""") }
        val client = signedInClient(backend, InMemoryRefreshTokenStore("refresh-0"))

        client.execute(ApiRequest("GET", endpoints.me))
        assertNull(backend.requestsTo(endpoints.me).last().headers["Content-Type"])

        client.execute(ApiRequest("POST", endpoints.me, body = "{}"))
        assertEquals(
            "application/json; charset=utf-8",
            backend.requestsTo(endpoints.me).last().headers["Content-Type"],
        )
    }

    @Test
    fun `the display name falls back to the email local part`() {
        val user = AuthUser("u-1", "patient@example.com", "patient", "active")
        assertEquals("patient", user.fallbackDisplayName)
    }
}
