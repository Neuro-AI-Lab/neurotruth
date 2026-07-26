package com.neurotruth.mobile.core.net

import com.neurotruth.mobile.core.ConsentSelection
import org.json.JSONObject

/**
 * The single authenticated seam. No screen implements 401 handling of its own.
 *
 * On a 401 the client refreshes exactly once and replays the original request exactly once. A
 * second 401 clears the session and hands control back to login — there is never a third attempt.
 *
 * Access tokens live only in memory. Only the refresh token is persisted, and only through
 * [RefreshTokenStore], which the Android layer backs with the Keystore.
 */
class AuthenticatedApiClient(
    private val endpoints: ApiEndpoints,
    private val transport: ApiTransport,
    private val sessionStore: RefreshTokenStore,
) {
    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var currentUser: AuthUser? = null

    @Volatile
    private var currentConsent: ConsentSelection? = null

    fun user(): AuthUser? = currentUser

    fun consent(): ConsentSelection? = currentConsent

    fun hasSession(): Boolean = accessToken != null || sessionStore.loadRefreshToken() != null

    fun execute(request: ApiRequest): ApiResponse {
        val initialToken = ensureAccessToken() ?: throw AuthenticationRequiredException()

        val first = transport.execute(withDefaults(request).withBearer(initialToken))
        if (first.statusCode != HTTP_UNAUTHORIZED) return first

        val rotated = rotate(initialToken) ?: throw AuthenticationRequiredException()
        val retried = transport.execute(withDefaults(request).withBearer(rotated))
        if (retried.statusCode == HTTP_UNAUTHORIZED) {
            // The refresh above succeeded, so the credential is valid and this 401 is the
            // endpoint's own answer rather than an expired session.
            if (request.treatUnauthorizedAsResponse) return retried
            clearSession()
            throw AuthenticationRequiredException()
        }
        return retried
    }

    /**
     * Same contract for calls that cannot be modelled as request/response — SSE and multipart.
     * [block] must throw [HttpStatusException] with 401 for the refresh to engage.
     */
    fun <T> executeStreaming(block: (accessToken: String) -> T): T {
        val initialToken = ensureAccessToken() ?: throw AuthenticationRequiredException()
        return try {
            block(initialToken)
        } catch (error: HttpStatusException) {
            if (error.statusCode != HTTP_UNAUTHORIZED) throw error
            val rotated = rotate(initialToken) ?: throw AuthenticationRequiredException()
            try {
                block(rotated)
            } catch (retryError: HttpStatusException) {
                if (retryError.statusCode == HTTP_UNAUTHORIZED) {
                    clearSession()
                    throw AuthenticationRequiredException()
                }
                throw retryError
            }
        }
    }

    /**
     * Guards against a thundering herd: if another caller already rotated the token while this one
     * was in flight, reuse theirs instead of burning a second refresh and invalidating it.
     */
    private fun rotate(usedToken: String): String? = synchronized(this) {
        val current = accessToken
        if (current != null && current != usedToken) return current
        val refreshToken = sessionStore.loadRefreshToken() ?: return null
        return runCatching { refresh(refreshToken).accessToken }.getOrNull()
    }

    /**
     * The access token to open a call with, refreshing from the stored refresh token if there is
     * none yet. Re-checks under the monitor so two cold-start callers don't both refresh — the
     * second sees the token the first already obtained.
     */
    private fun ensureAccessToken(): String? = synchronized(this) {
        accessToken?.let { return it }
        val refreshToken = sessionStore.loadRefreshToken() ?: return null
        return runCatching { refresh(refreshToken).accessToken }.getOrNull()
    }

    fun signup(request: PatientSignupRequest): AuthTokens =
        adopt(post(endpoints.patientSignup, request.toJson()))

    fun login(email: String, password: String, device: String? = null): AuthTokens =
        adopt(post(endpoints.login, LoginRequest.toJson(email, password, device)))

    fun refresh(refreshToken: String): AuthTokens {
        val response = post(endpoints.refresh, JSONObject().put("refreshToken", refreshToken).toString())
        return adopt(response)
    }

    fun appendConsent(consent: ConsentSelection): ConsentSelection {
        val response = execute(
            ApiRequest("POST", endpoints.consents, body = consent.toJson().toString()),
        )
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        currentConsent = consent
        return consent
    }

    fun logout() {
        val refreshToken = sessionStore.loadRefreshToken()
        if (refreshToken == null) {
            clearSession()
            return
        }
        val response = transport.execute(
            withDefaults(
                ApiRequest(
                    "POST",
                    endpoints.logout,
                    body = JSONObject().put("refreshToken", refreshToken).toString(),
                ),
            ),
        )
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        clearSession()
    }

    fun recoverSession(): AuthTokens? = synchronized(this) {
        val refreshToken = sessionStore.loadRefreshToken() ?: return null
        return runCatching { refresh(refreshToken) }.getOrNull()
    }

    /**
     * Holds the same monitor as [rotate]/[recoverSession] so a logout cannot be undone by an
     * in-flight refresh. Without the lock, a background 401 that is mid-refresh (holding the lock)
     * could complete after this ran and [adopt] would write a fresh token back — resurrecting a
     * session the user just cleared. With the lock, clear waits for the refresh to finish and then
     * wins.
     */
    fun clearSession() = synchronized(this) {
        accessToken = null
        currentUser = null
        currentConsent = null
        sessionStore.clear()
    }

    private fun post(url: String, body: String): ApiResponse =
        transport.execute(withDefaults(ApiRequest("POST", url, body = body)))

    private fun adopt(response: ApiResponse): AuthTokens {
        if (!response.isSuccessful) {
            clearSession()
            throw ApiHttpException(response.statusCode, response.body)
        }
        val tokens = runCatching { AuthResponseParser.parse(response.body) }
            .getOrElse {
                clearSession()
                throw ApiHttpException(response.statusCode, response.body)
            }
        // Under the same monitor as clearSession(): the session-adopting writes and a concurrent
        // logout are serialized rather than interleaved. Reentrant when called from rotate().
        synchronized(this) {
            accessToken = tokens.accessToken
            currentUser = tokens.user
            tokens.consent?.let { currentConsent = it }
            sessionStore.saveRefreshToken(tokens.refreshToken)
        }
        return tokens
    }

    private fun withDefaults(request: ApiRequest): ApiRequest {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        if (request.body != null && request.headers.keys.none { it.equals("Content-Type", true) }) {
            headers["Content-Type"] = "application/json; charset=utf-8"
        }
        headers.putAll(request.headers)
        return request.copy(headers = headers)
    }

    companion object {
        const val HTTP_UNAUTHORIZED: Int = 401
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 8_000
        const val DEFAULT_READ_TIMEOUT_MS: Int = 20_000
    }
}
