package com.example.healthsensor

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

data class ApiRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val connectTimeoutMs: Int? = null,
    val readTimeoutMs: Int? = null
)

data class ApiResponse(val statusCode: Int, val body: String)

fun interface ApiTransport {
    fun execute(request: ApiRequest): ApiResponse
}

class UrlConnectionApiTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 20_000
) : ApiTransport {
    override fun execute(request: ApiRequest): ApiResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = request.connectTimeoutMs ?: connectTimeoutMs
            readTimeout = request.readTimeoutMs ?: readTimeoutMs
            doOutput = request.body != null
            setRequestProperty("Accept", "application/json")
            request.headers.forEach(::setRequestProperty)
            if (request.body != null && request.headers.keys.none { it.equals("Content-Type", true) }) {
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            request.body?.let { body ->
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            ApiResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

class ApiEndpoints(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/').also {
        require(it.startsWith("http://") || it.startsWith("https://")) {
            "baseUrl must be HTTP(S)"
        }
    }

    val patientSignup = "$base/api/auth/patient/signup"
    val login = "$base/api/auth/login"
    val refresh = "$base/api/auth/refresh"
    val logout = "$base/api/auth/logout"
    val changePassword = "$base/api/auth/change-password"
    val me = "$base/api/me"
    val consents = "$base/api/me/consents"
    val sensorWindows = "$base/api/sensor-windows"
    val predictionStream = "$base/api/predictions/stream"
    val sessions = "$base/api/sessions"
    val sttStatus = "$base/api/stt/status"
    val rppgStatus = "$base/api/rppg/status"
    val rppgJobs = "$base/api/rppg/jobs"
    val patientDashboard = "$base/api/me/dashboard"
    val cravingProbabilitySeries = "$base/api/me/craving-probability-series"
    val cravingDashboard = "$base/api/me/craving-dashboard"

    fun session(sessionId: String): String = "$sessions/${requireUuid(sessionId)}"
    fun messages(sessionId: String): String = "${session(sessionId)}/messages"
    fun transcriptions(sessionId: String): String = "${session(sessionId)}/transcriptions"
    fun assessments(sessionId: String): String = "${session(sessionId)}/assessments"
    fun finish(sessionId: String): String = "${session(sessionId)}/finish"
    fun reports(sessionId: String): String = "${session(sessionId)}/reports"
    fun rppgJob(jobId: String): String = "$rppgJobs/${requireUuid(jobId)}"
    fun retryRppgJob(jobId: String): String = "${rppgJob(jobId)}/retry"
    fun dashboard(range: String): String = "$patientDashboard?range=$range"
    fun cravingProbabilitySeries(range: String): String = "$cravingProbabilitySeries?range=$range"
    fun cravingDashboard(timezone: String, eventRange: String, auqRange: String): String {
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        return "$cravingDashboard?timezone=${encode(timezone)}&eventRange=${encode(eventRange)}&auqRange=${encode(auqRange)}"
    }
    fun ppgPreview(predictionId: String): String =
        "$base/api/me/predictions/${requireUuid(predictionId)}/ppg-preview"

    private fun requireUuid(value: String): String =
        java.util.UUID.fromString(value).toString()
}

class ApiHttpException(val statusCode: Int) : IllegalStateException("HTTP $statusCode")
class AuthenticationRequiredException : IllegalStateException("authentication required")

/**
 * Owns the phone's access token and refresh rotation. It retries an authenticated request
 * exactly once after a 401. Refresh credentials are delegated to a Keystore-backed store.
 */
class AuthenticatedApiClient(
    val endpoints: ApiEndpoints,
    private val sessionStore: AuthSessionStore,
    private val transport: ApiTransport = UrlConnectionApiTransport()
) {
    @Volatile
    private var accessToken: String? = null

    fun signupPatient(request: PatientSignupRequest): AuthTokens = acceptAuthResponse(
        executeSuccessful(ApiRequest("POST", endpoints.patientSignup, JSON_HEADERS, request.toJson()))
    )

    fun login(request: LoginRequest): AuthTokens = acceptAuthResponse(
        executeSuccessful(ApiRequest("POST", endpoints.login, JSON_HEADERS, request.toJson()))
    )

    fun appendConsent(consent: ConsentSelection): ConsentSelection {
        val response = executeAuthenticated(
            ApiRequest("POST", endpoints.consents, JSON_HEADERS, consent.toJson().toString())
        )
        if (response.statusCode !in 200..299) throw ApiHttpException(response.statusCode)
        return ConsentSelection.fromJson(JSONObject(response.body))
            ?: throw IllegalArgumentException("consent snapshot missing")
    }

    @Synchronized
    fun recoverSession(): AuthTokens? {
        val refreshToken = sessionStore.loadRefreshToken() ?: return null
        return refresh(refreshToken)
    }

    fun executeAuthenticated(request: ApiRequest): ApiResponse {
        val initialToken = accessToken ?: recoverSession()?.accessToken
            ?: throw AuthenticationRequiredException()
        val first = transport.execute(request.withBearer(initialToken))
        if (first.statusCode != HttpURLConnection.HTTP_UNAUTHORIZED) return first

        val rotated = synchronized(this) {
            if (accessToken != null && accessToken != initialToken) {
                accessToken
            } else {
                sessionStore.loadRefreshToken()?.let(::refresh)?.accessToken
            }
        } ?: throw AuthenticationRequiredException()
        val retried = transport.execute(request.withBearer(rotated))
        if (retried.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            clearSession()
            throw AuthenticationRequiredException()
        }
        return retried
    }

    fun <T> executeAuthenticatedStream(block: (accessToken: String) -> T): T {
        val initialToken = accessToken ?: recoverSession()?.accessToken
            ?: throw AuthenticationRequiredException()
        return try {
            block(initialToken)
        } catch (error: HttpStatusException) {
            if (error.statusCode != HttpURLConnection.HTTP_UNAUTHORIZED) throw error
            val rotated = synchronized(this) {
                if (accessToken != null && accessToken != initialToken) {
                    accessToken
                } else {
                    sessionStore.loadRefreshToken()?.let(::refresh)?.accessToken
                }
            } ?: throw AuthenticationRequiredException()
            try {
                block(rotated)
            } catch (retried: HttpStatusException) {
                if (retried.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    clearSession()
                    throw AuthenticationRequiredException()
                }
                throw retried
            }
        }
    }

    fun logout() {
        val refreshToken = sessionStore.loadRefreshToken()
        try {
            if (refreshToken != null) {
                val request = ApiRequest(
                    "POST",
                    endpoints.logout,
                    JSON_HEADERS,
                    JSONObject().put("refreshToken", refreshToken).toString()
                )
                val token = accessToken
                val response = transport.execute(if (token == null) request else request.withBearer(token))
                if (response.statusCode !in 200..299 && response.statusCode != 401) {
                    throw ApiHttpException(response.statusCode)
                }
            }
        } finally {
            clearSession()
        }
    }

    fun clearSession() {
        accessToken = null
        sessionStore.clear()
    }

    internal fun currentAccessToken(): String? = accessToken

    private fun refresh(refreshToken: String): AuthTokens {
        val request = ApiRequest(
            "POST",
            endpoints.refresh,
            JSON_HEADERS,
            JSONObject().put("refreshToken", refreshToken).toString()
        )
        val response = transport.execute(request)
        if (response.statusCode !in 200..299) {
            clearSession()
            throw ApiHttpException(response.statusCode)
        }
        return runCatching { acceptAuthResponse(response) }
            .getOrElse {
                clearSession()
                throw it
            }
    }

    private fun executeSuccessful(request: ApiRequest): ApiResponse {
        val response = transport.execute(request)
        if (response.statusCode !in 200..299) throw ApiHttpException(response.statusCode)
        return response
    }

    private fun acceptAuthResponse(response: ApiResponse): AuthTokens {
        val auth = AuthResponseParser.parse(response.body)
        accessToken = auth.accessToken
        sessionStore.saveRefreshToken(auth.refreshToken)
        return auth
    }

    private fun ApiRequest.withBearer(token: String): ApiRequest = copy(
        headers = headers + mapOf("Authorization" to "Bearer $token")
    )

    companion object {
        private val JSON_HEADERS = mapOf("Content-Type" to "application/json; charset=utf-8")
    }
}

object MobileApiProvider {
    @Volatile
    private var client: AuthenticatedApiClient? = null

    fun get(context: android.content.Context): AuthenticatedApiClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: AuthenticatedApiClient(
                endpoints = ApiEndpoints(ServerConfig.load(context.applicationContext).apiBaseUrl()),
                sessionStore = KeystoreAuthSessionStore(context.applicationContext)
            ).also { client = it }
        }
    }

    internal fun replaceForTests(value: AuthenticatedApiClient?) {
        client = value
    }
}
