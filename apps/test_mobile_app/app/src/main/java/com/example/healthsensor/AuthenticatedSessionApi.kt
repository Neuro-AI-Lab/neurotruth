package com.example.healthsensor

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

data class SafetySupportResource(val label: String, val contact: String)

data class SessionSafety(
    val status: String,
    val riskCodes: List<String>,
    val supportResources: List<SafetySupportResource>
)

data class SessionIntervention(
    val id: String,
    val type: String,
    val status: String,
    val presentationOrder: Int,
    val content: String,
    val createdAtMs: Long
)

data class SessionStateSnapshot(
    val inferenceId: String,
    val scope: String,
    val state: String,
    val confidence: Float?,
    val summaryStatus: String,
    val summary: String?,
    val createdAtMs: Long
)

data class ConversationSession(
    val id: String,
    val status: String,
    val interactionPhase: String?,
    val assistantText: String?,
    val safety: SessionSafety?,
    val activeInterventions: List<SessionIntervention>,
    val stateSnapshot: SessionStateSnapshot?,
    val reportStatus: String,
    val inactivityTimeoutSeconds: Int?,
    val legacy: Boolean,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val endedAtMs: Long? = null
) {
    val active: Boolean get() = status == "in_progress" && !legacy
    val terminal: Boolean get() = status in setOf("completed", "abandoned")
}

data class SessionMessageResponse(
    val userMessageId: String,
    val assistantMessageId: String,
    val assistantText: String,
    val phase: String,
    val safety: SessionSafety,
    val activeInterventions: List<SessionIntervention>,
    val stateSnapshot: SessionStateSnapshot?,
    val reportStatus: String,
    val inactivityTimeoutSeconds: Int?
) {
    val terminal: Boolean get() = phase in setOf("completed", "abandoned")
}

data class SttTranscript(
    val text: String,
    val language: String,
    val durationMs: Long,
    val model: String
)

class DialogueRequestException(
    val code: String,
    val clientMessageId: String?,
    val userMessageId: String?,
    val retryable: Boolean,
    val attemptsRemaining: Int
) : IllegalStateException(code)

data class SessionReportResponse(
    val reportId: String?,
    val version: Int?,
    val status: String,
    val createdAtMs: Long?,
    val generatedAtMs: Long?
) {
    val completed: Boolean get() = status == "ready"
    val failed: Boolean get() = status == "failed"
}

interface SessionApi {
    fun create(sessionType: String, triggerAlertId: String? = null): ConversationSession
    fun get(sessionId: String): ConversationSession
    fun postMessage(
        sessionId: String,
        clientMessageId: String?,
        content: String,
        readTimeoutMs: Int,
        inputModality: String = "text"
    ): SessionMessageResponse
    fun postAssessment(sessionId: String, result: StateCheckResult)
    fun finish(sessionId: String): ConversationSession
    fun requestReport(sessionId: String): SessionReportResponse
    fun getReport(sessionId: String): SessionReportResponse
}

class AuthenticatedSessionApi(private val client: AuthenticatedApiClient) : SessionApi {
    override fun create(sessionType: String, triggerAlertId: String?): ConversationSession {
        val body = JSONObject()
            .put("sessionType", sessionType)
            .put("triggerAlertId", triggerAlertId ?: JSONObject.NULL)
        val response = client.executeAuthenticated(
            ApiRequest("POST", client.endpoints.sessions, JSON_HEADERS, body.toString())
        )
        if (response.statusCode !in 200..299 && response.statusCode != 409) {
            throw ApiHttpException(response.statusCode)
        }
        return SessionResponseParser.parseSession(response.body)
    }

    override fun get(sessionId: String): ConversationSession =
        successful(client.executeAuthenticated(ApiRequest("GET", client.endpoints.session(sessionId))))
            .let { SessionResponseParser.parseSession(it.body) }

    override fun postMessage(
        sessionId: String,
        clientMessageId: String?,
        content: String,
        readTimeoutMs: Int,
        inputModality: String
    ): SessionMessageResponse {
        require(inputModality in setOf("text", "voice")) { "unsupported input modality" }
        val body = JSONObject()
            .put("content", content)
            .put("inputModality", inputModality)
        if (clientMessageId != null) {
            body.put("clientMessageId", UUID.fromString(clientMessageId).toString())
        }
        val response = client.executeAuthenticated(
            ApiRequest(
                "POST",
                client.endpoints.messages(sessionId),
                JSON_HEADERS,
                body.toString(),
                connectTimeoutMs = 8_000,
                readTimeoutMs = readTimeoutMs
            )
        )
        if (response.statusCode == 502) throw SessionResponseParser.parseDialogueError(response.body)
        return successful(response).let { SessionResponseParser.parseMessage(it.body) }
    }

    fun transcribe(sessionId: String, audio: File): SttTranscript {
        require(audio.isFile) { "audio missing" }
        return client.executeAuthenticatedStream { token ->
            val boundary = "----NeuroTruth-${UUID.randomUUID()}"
            val connection = (URL(client.endpoints.transcriptions(sessionId)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 120_000
                doOutput = true
                setChunkedStreamingMode(32 * 1024)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            try {
                BufferedOutputStream(connection.outputStream).use { output ->
                    output.writeUtf8("--$boundary\r\n")
                    output.writeUtf8("Content-Disposition: form-data; name=\"language\"\r\n\r\nko\r\n")
                    output.writeUtf8("--$boundary\r\n")
                    output.writeUtf8("Content-Disposition: form-data; name=\"audio\"; filename=\"voice.m4a\"\r\n")
                    output.writeUtf8("Content-Type: audio/mp4\r\n\r\n")
                    audio.inputStream().use { input -> input.copyTo(output, 32 * 1024) }
                    output.writeUtf8("\r\n--$boundary--\r\n")
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw HttpStatusException(status, "authentication required")
                }
                if (status !in 200..299) throw ApiHttpException(status)
                val root = JSONObject(responseBody)
                SttTranscript(
                    text = root.optString("text").trim().takeIf(String::isNotEmpty)
                        ?: throw IllegalArgumentException("transcript missing"),
                    language = root.optString("language", "ko"),
                    durationMs = root.optLong("durationMs", 0L),
                    model = root.optString("model")
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun BufferedOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }

    override fun postAssessment(sessionId: String, result: StateCheckResult) {
        successful(
            client.executeAuthenticated(
                ApiRequest(
                    "POST",
                    client.endpoints.assessments(sessionId),
                    JSON_HEADERS,
                    JSONObject()
                        .put("instrumentCode", "AUQ")
                        .put("version", "2.0")
                        .put("phase", "pre_intervention")
                        .put("attemptNo", 1)
                        .put("answers", JSONObject().apply {
                            put("responses", JSONArray(result.responses))
                            put("scoredItems", JSONArray(result.scoredItems))
                            put("capturedAtMs", result.timestampMs)
                            put("triggerClass", result.triggerClass ?: JSONObject.NULL)
                            put("rawTotalScore", result.rawTotalScore)
                        })
                        .put("rawScore", result.totalScore.toDouble())
                        .put("scaleMin", 0.0)
                        .put("scaleMax", result.responses.size * StateCheckScoring.MAX_RESPONSE.toDouble())
                        .toString()
                )
            )
        )
    }

    override fun finish(sessionId: String): ConversationSession = successful(
        client.executeAuthenticated(ApiRequest("POST", client.endpoints.finish(sessionId)))
    ).let {
        SessionResponseParser.parseSession(
            it.body,
            fallbackId = sessionId,
            fallbackStatus = "completed",
            fallbackPhase = "completed"
        )
    }

    override fun requestReport(sessionId: String): SessionReportResponse = successful(
        client.executeAuthenticated(ApiRequest("POST", client.endpoints.reports(sessionId), JSON_HEADERS, "{}"))
    ).let { SessionResponseParser.parseReport(it.body) }

    override fun getReport(sessionId: String): SessionReportResponse = successful(
        client.executeAuthenticated(ApiRequest("GET", client.endpoints.reports(sessionId)))
    ).let { SessionResponseParser.parseReport(it.body) }

    private fun successful(response: ApiResponse): ApiResponse {
        if (response.statusCode !in 200..299) throw ApiHttpException(response.statusCode)
        return response
    }

    companion object {
        private val JSON_HEADERS = mapOf("Content-Type" to "application/json; charset=utf-8")
    }
}

object SessionResponseParser {
    fun parseSession(
        body: String,
        fallbackId: String? = null,
        fallbackStatus: String = "in_progress",
        fallbackPhase: String? = null
    ): ConversationSession {
        val root = body.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        val value = root.optJSONObject("session") ?: root.optJSONObject("existingSession") ?: root
        val id = value.cleanString("id") ?: value.cleanString("sessionId") ?: fallbackId
            ?: throw IllegalArgumentException("session id missing")
        val normalizedId = UUID.fromString(id).toString()
        val now = System.currentTimeMillis()
        val created = value.timeValue("createdAtMs", "createdAt") ?: now
        val started = value.timeValue("startedAtMs", "startedAt") ?: created
        val ended = value.timeValue("endedAtMs", "endedAt")
        return ConversationSession(
            id = normalizedId,
            status = (value.cleanString("status") ?: fallbackStatus).lowercase(),
            interactionPhase = value.cleanString("interactionPhase") ?: fallbackPhase,
            assistantText = value.cleanString("assistantText"),
            safety = value.optJSONObject("safety")?.toSafety(),
            activeInterventions = value.optJSONArray("activeInterventions").toInterventions(),
            stateSnapshot = value.optJSONObject("stateSnapshot")?.toStateSnapshot(),
            reportStatus = (value.cleanString("reportStatus") ?: "not_started").lowercase(),
            inactivityTimeoutSeconds = value.intValue("inactivityTimeoutSeconds")?.takeIf { it > 0 },
            legacy = value.optBoolean("legacy", false),
            startedAtMs = started,
            updatedAtMs = value.timeValue("updatedAtMs", "updatedAt")
                ?: value.timeValue("lastActivityAtMs", "lastActivityAt")
                ?: ended
                ?: started,
            endedAtMs = ended
        )
    }

    fun parseMessage(body: String): SessionMessageResponse {
        val root = JSONObject(body)
        return SessionMessageResponse(
            userMessageId = root.uuid("userMessageId"),
            assistantMessageId = root.uuid("assistantMessageId"),
            assistantText = root.cleanString("assistantText").orEmpty(),
            phase = (root.cleanString("phase") ?: "free_dialogue").lowercase(),
            safety = root.optJSONObject("safety")?.toSafety()
                ?: SessionSafety("clear", emptyList(), emptyList()),
            activeInterventions = root.optJSONArray("activeInterventions").toInterventions(),
            stateSnapshot = root.optJSONObject("stateSnapshot")?.toStateSnapshot(),
            reportStatus = (root.cleanString("reportStatus") ?: "not_started").lowercase(),
            inactivityTimeoutSeconds = root.intValue("inactivityTimeoutSeconds")?.takeIf { it > 0 }
        )
    }

    fun parseDialogueError(body: String): DialogueRequestException {
        val root = body.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        val detail = root.optJSONObject("detail") ?: root
        return DialogueRequestException(
            code = detail.cleanString("code") ?: "dialogue_provider_error",
            clientMessageId = detail.cleanString("clientMessageId")?.let { UUID.fromString(it).toString() },
            userMessageId = detail.cleanString("userMessageId")?.let { UUID.fromString(it).toString() },
            retryable = detail.optBoolean("retryable", false),
            attemptsRemaining = (detail.intValue("attemptsRemaining") ?: 0).coerceAtLeast(0)
        )
    }

    fun parseReport(body: String): SessionReportResponse {
        val trimmed = body.trim()
        val reports = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.isBlank() -> JSONArray()
            else -> JSONObject(trimmed).optJSONArray("reports")
        }
        val root = when {
            reports != null && reports.length() > 0 -> reports.optJSONObject(0) ?: JSONObject()
            trimmed.startsWith("{") -> JSONObject(trimmed).optJSONObject("report") ?: JSONObject(trimmed)
            else -> JSONObject()
        }
        return SessionReportResponse(
            reportId = root.cleanString("reportId"),
            version = root.intValue("version"),
            status = (root.cleanString("status") ?: "not_started").lowercase(),
            createdAtMs = root.timeValue("createdAtMs", "createdAt"),
            generatedAtMs = root.timeValue("generatedAtMs", "generatedAt")
        )
    }

    private fun JSONObject.toSafety(): SessionSafety = SessionSafety(
        status = (cleanString("status") ?: "awaiting_response").lowercase(),
        riskCodes = stringList("riskCodes"),
        supportResources = optJSONArray("supportResources").let { values ->
            if (values == null) emptyList() else (0 until values.length()).mapNotNull { index ->
                val item = values.optJSONObject(index) ?: return@mapNotNull null
                val label = item.cleanString("label") ?: return@mapNotNull null
                val contact = item.cleanString("contact") ?: return@mapNotNull null
                SafetySupportResource(label, contact)
            }
        }
    )

    private fun JSONArray?.toInterventions(): List<SessionIntervention> {
        this ?: return emptyList()
        return (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            val id = item.cleanString("id") ?: return@mapNotNull null
            val type = item.cleanString("type") ?: return@mapNotNull null
            val content = item.cleanString("content") ?: return@mapNotNull null
            SessionIntervention(
                id = UUID.fromString(id).toString(),
                type = type,
                status = item.cleanString("status") ?: "delivered",
                presentationOrder = item.intValue("presentationOrder") ?: 1,
                content = content,
                createdAtMs = item.timeValue("createdAtMs", "createdAt") ?: System.currentTimeMillis()
            )
        }
    }

    private fun JSONObject.toStateSnapshot(): SessionStateSnapshot? {
        val id = cleanString("inferenceId") ?: return null
        return SessionStateSnapshot(
            inferenceId = UUID.fromString(id).toString(),
            scope = cleanString("scope") ?: "realtime",
            state = cleanString("state") ?: "unknown",
            confidence = floatValue("confidence"),
            summaryStatus = cleanString("summaryStatus") ?: "pending",
            summary = cleanString("summary"),
            createdAtMs = timeValue("createdAtMs", "createdAt") ?: System.currentTimeMillis()
        )
    }

    private fun JSONObject.timeValue(numericKey: String, isoKey: String): Long? =
        longValue(numericKey) ?: cleanString(isoKey)?.let { value ->
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        }

    private fun JSONObject.cleanString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf(String::isNotBlank)

    private fun JSONObject.uuid(key: String): String =
        cleanString(key)?.let { UUID.fromString(it).toString() }
            ?: throw IllegalArgumentException("$key missing")

    private fun JSONObject.longValue(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.intValue(key: String): Int? = longValue(key)?.toInt()

    private fun JSONObject.floatValue(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is Number -> value.toFloat().takeIf(Float::isFinite)
            is String -> value.toFloatOrNull()?.takeIf(Float::isFinite)
            else -> null
        }
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val values = optJSONArray(key) ?: return emptyList()
        return (0 until values.length()).mapNotNull { index ->
            values.optString(index).trim().takeIf(String::isNotBlank)
        }
    }
}

interface ConversationSessionStore {
    fun load(ownerId: String): ConversationSession?
    fun save(ownerId: String, session: ConversationSession)
    fun loadReportTarget(ownerId: String): ConversationSession?
    fun saveReportTarget(ownerId: String, session: ConversationSession)
    fun clearActive()
    fun clear()
}

class SharedPreferencesConversationSessionStore(context: Context) : ConversationSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences("conversation_session", Context.MODE_PRIVATE)

    override fun load(ownerId: String): ConversationSession? = loadWithPrefix(ownerId, "active")

    override fun save(ownerId: String, session: ConversationSession) = saveWithPrefix(ownerId, session, "active")

    override fun loadReportTarget(ownerId: String): ConversationSession? = loadWithPrefix(ownerId, "report")

    override fun saveReportTarget(ownerId: String, session: ConversationSession) = saveWithPrefix(ownerId, session, "report")

    private fun loadWithPrefix(ownerId: String, prefix: String): ConversationSession? {
        if (preferences.getString("${prefix}_owner", null) != ownerId) return null
        return runCatching {
            ConversationSession(
                id = UUID.fromString(preferences.getString("${prefix}_id", null)).toString(),
                status = preferences.getString("${prefix}_status", null) ?: return null,
                interactionPhase = preferences.getString("${prefix}_phase", null),
                assistantText = null,
                safety = null,
                activeInterventions = emptyList(),
                stateSnapshot = null,
                reportStatus = preferences.getString("${prefix}_report_status", "not_started") ?: "not_started",
                inactivityTimeoutSeconds = preferences.getInt("${prefix}_timeout_seconds", 0).takeIf { it > 0 },
                legacy = preferences.getBoolean("${prefix}_legacy", false),
                startedAtMs = preferences.getLong("${prefix}_started", 0L),
                updatedAtMs = preferences.getLong("${prefix}_updated", 0L),
                endedAtMs = preferences.getLong("${prefix}_ended", 0L).takeIf { it > 0L }
            )
        }.getOrNull()
    }

    private fun saveWithPrefix(ownerId: String, session: ConversationSession, prefix: String) {
        preferences.edit()
            .putString("${prefix}_owner", ownerId)
            .putString("${prefix}_id", session.id)
            .putString("${prefix}_status", session.status)
            .putString("${prefix}_phase", session.interactionPhase)
            .putString("${prefix}_report_status", session.reportStatus)
            .putInt("${prefix}_timeout_seconds", session.inactivityTimeoutSeconds ?: 0)
            .putBoolean("${prefix}_legacy", session.legacy)
            .putLong("${prefix}_started", session.startedAtMs)
            .putLong("${prefix}_updated", session.updatedAtMs)
            .putLong("${prefix}_ended", session.endedAtMs ?: 0L)
            .apply()
    }

    override fun clearActive() {
        preferences.edit().apply {
            preferences.all.keys.filter { it.startsWith("active_") }.forEach(::remove)
        }.apply()
    }

    override fun clear() = preferences.edit().clear().apply()
}

class ConversationSessionManager(
    private val api: SessionApi,
    private val store: ConversationSessionStore,
    private val timeoutMs: () -> Long
) {
    private val mutex = Mutex()

    suspend fun start(
        ownerId: String,
        sessionType: String,
        triggerAlertId: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): ConversationSession = startWithCreationState(
        ownerId = ownerId,
        sessionType = sessionType,
        triggerAlertId = triggerAlertId,
        nowMs = nowMs
    ).first

    suspend fun startWithCreationState(
        ownerId: String,
        sessionType: String,
        triggerAlertId: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<ConversationSession, Boolean> = mutex.withLock {
        val saved = store.load(ownerId)
        if (saved != null && saved.active) {
            val remote = runCatching { api.get(saved.id) }.getOrNull()
            if (remote?.active == true) {
                store.save(ownerId, remote.copy(updatedAtMs = nowMs))
                return@withLock remote to false
            }
            if (remote?.terminal == true) store.saveReportTarget(ownerId, remote)
        }
        store.clearActive()
        val created = api.create(sessionType, triggerAlertId)
        require(created.active) { "server returned non-active session" }
        store.save(ownerId, created)
        created to true
    }

    suspend fun ensure(ownerId: String, nowMs: Long = System.currentTimeMillis()): ConversationSession =
        start(ownerId, "manual_checkin", null, nowMs)

    suspend fun ensureWithCreationState(
        ownerId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<ConversationSession, Boolean> =
        startWithCreationState(ownerId, "manual_checkin", null, nowMs)

    suspend fun postMessage(
        ownerId: String,
        clientMessageId: String?,
        content: String,
        readTimeoutMs: Int,
        inputModality: String = "text"
    ): SessionMessageResponse {
        val session = ensure(ownerId)
        val response = api.postMessage(session.id, clientMessageId, content, readTimeoutMs, inputModality)
        val status = if (response.phase in setOf("completed", "abandoned")) response.phase else "in_progress"
        val updated = session.copy(
            status = status,
            interactionPhase = response.phase,
            safety = response.safety,
            activeInterventions = response.activeInterventions,
            stateSnapshot = response.stateSnapshot,
            reportStatus = response.reportStatus,
            inactivityTimeoutSeconds = response.inactivityTimeoutSeconds ?: session.inactivityTimeoutSeconds,
            updatedAtMs = System.currentTimeMillis(),
            endedAtMs = if (status in setOf("completed", "abandoned")) System.currentTimeMillis() else null
        )
        store.save(ownerId, updated)
        if (updated.terminal) store.saveReportTarget(ownerId, updated)
        return response
    }

    suspend fun postAssessment(ownerId: String, result: StateCheckResult) {
        val session = ensure(ownerId)
        api.postAssessment(session.id, result)
        store.save(ownerId, session.copy(updatedAtMs = System.currentTimeMillis()))
    }

    suspend fun finish(ownerId: String): ConversationSession? = mutex.withLock {
        val saved = store.load(ownerId) ?: return@withLock null
        val finished = api.finish(saved.id)
        store.save(ownerId, finished)
        store.saveReportTarget(ownerId, finished)
        finished
    }

    suspend fun requestReport(ownerId: String): SessionReportResponse {
        val session = store.loadReportTarget(ownerId) ?: store.load(ownerId)
            ?: throw IllegalStateException("report session missing")
        return api.requestReport(session.id)
    }

    fun getReport(ownerId: String): SessionReportResponse {
        val session = store.loadReportTarget(ownerId) ?: store.load(ownerId)
            ?: throw IllegalStateException("report session missing")
        return api.getReport(session.id)
    }

    fun clear() = store.clear()
}
