package com.neurotruth.mobile.core.net

data class ApiRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val connectTimeoutMs: Int? = null,
    val readTimeoutMs: Int? = null,
) {
    fun withBearer(accessToken: String): ApiRequest =
        copy(headers = headers + ("Authorization" to "Bearer $accessToken"))
}

data class ApiResponse(val statusCode: Int, val body: String) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/**
 * A single seam for HTTP.
 *
 * Keeping this a `fun interface` is what lets the 401/refresh flow be tested on the JVM with no
 * Android runtime and no network.
 */
fun interface ApiTransport {
    fun execute(request: ApiRequest): ApiResponse
}

/** Thrown from streaming calls (SSE, multipart) that cannot be modelled as request/response. */
class HttpStatusException(val statusCode: Int, message: String) : RuntimeException(message)

class ApiHttpException(val statusCode: Int, val responseBody: String = "") :
    RuntimeException("HTTP $statusCode")

/** The caller must return to login; refresh is exhausted or the credential is gone. */
class AuthenticationRequiredException(message: String = "authentication required") :
    RuntimeException(message)

interface RefreshTokenStore {
    fun loadRefreshToken(): String?
    fun saveRefreshToken(refreshToken: String): Boolean
    fun clear()
}

class InMemoryRefreshTokenStore(initial: String? = null) : RefreshTokenStore {
    private var token: String? = initial

    @Synchronized
    override fun loadRefreshToken(): String? = token

    @Synchronized
    override fun saveRefreshToken(refreshToken: String): Boolean {
        token = refreshToken
        return true
    }

    @Synchronized
    override fun clear() {
        token = null
    }
}
