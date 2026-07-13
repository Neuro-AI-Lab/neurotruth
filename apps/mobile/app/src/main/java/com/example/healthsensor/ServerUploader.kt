package com.example.healthsensor

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ServerSensorSample(
    val sensor: String,
    val timestampMs: Long,
    val value: Float
)

/**
 * 서버가 payload를 해석할 때 필요한 동기화 계약.
 *
 * samples는 flat list지만, PPG/EDA는 이 메타데이터에 맞춘 fixed timestamp grid로
 * 재샘플링되어 들어간다. fillMode는 실시간 전송을 끊지 않기 위해 gap을 어떻게 채웠는지
 * 서버가 알 수 있도록 함께 보낸다.
 */
data class ServerWindowSync(
    val mode: String,
    val fillMode: String,
    val ppgHz: Int,
    val ppgSamplesPerChannel: Int,
    val edaHz: Int,
    val edaSamples: Int
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("mode", mode)
            .put("fillMode", fillMode)
            .put("ppgHz", ppgHz)
            .put("ppgSamplesPerChannel", ppgSamplesPerChannel)
            .put("edaHz", edaHz)
            .put("edaSamples", edaSamples)
}

data class ServerWindowPayload(
    val sessionId: String,
    val sessionStartedAtMs: Long,
    val sequence: Long,
    val sentAtMs: Long,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val windowMs: Long,
    val samples: List<ServerSensorSample>,
    val sync: ServerWindowSync? = null
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("sessionId", sessionId)
        root.put("sessionStartedAtMs", sessionStartedAtMs)
        root.put("sequence", sequence)
        root.put("sentAtMs", sentAtMs)
        root.put("windowStartMs", windowStartMs)
        root.put("windowEndMs", windowEndMs)
        root.put("windowMs", windowMs)
        sync?.let { root.put("sync", it.toJson()) }

        val sampleArray = JSONArray()
        samples.forEach { sample ->
            sampleArray.put(
                JSONObject()
                    .put("sensor", sample.sensor)
                    .put("timestampMs", sample.timestampMs)
                    .put("value", sample.value.toDouble())
            )
        }
        root.put("samples", sampleArray)
        return root.toString()
    }
}

data class UploadResult(
    val success: Boolean,
    val statusCode: Int,
    val message: String
)

data class AlertMetadata(
    val alertLevel: String = "none",
    val alertAction: String? = null,
    val windowMean: Float? = null,
    val triggerReason: String? = null,
    val alertRequired: Boolean = false,
    val isPresent: Boolean = false
) {
    val label: String
        get() = when (alertLevel.lowercase(Locale.US)) {
            "recommend", "recommendation" -> "권장"
            "required" -> "필수"
            else -> "없음"
        }

    val colorLevel: String
        get() = when (alertLevel.lowercase(Locale.US)) {
            "recommend", "recommendation" -> "recommend"
            "required" -> "required"
            else -> "none"
        }

    fun toJson(): JSONObject =
        JSONObject()
            .put("alertLevel", colorLevel)
            .put("alertRequired", alertRequired)
            .apply {
                alertAction?.let { put("alertAction", it) }
                windowMean?.let { put("windowMean", it.toDouble()) }
                triggerReason?.let { put("triggerReason", it) }
            }
}

data class CravingPrediction(
    val cravingClass: Int,
    val timestampMs: Long,
    val rawBody: String,
    val score: Float? = null,
    val confidence: Float? = null,
    val sequence: Long? = null,
    val uploadSentAtMs: Long? = null,
    val hasServerTimestamp: Boolean = false,
    val sessionId: String? = null,
    val alert: AlertMetadata = AlertMetadata()
) {
    val label: String
        get() = when (cravingClass) {
            0 -> "0 낮음"
            1 -> "1 중간"
            2 -> "2 높음"
            else -> "$cravingClass 알 수 없음"
        }
}

data class InterventionMessage(
    val role: String,
    val content: String
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("role", role)
            .put("content", content)
}

data class InterventionChatRequest(
    val sessionId: String,
    val message: String,
    val alert: AlertMetadata?,
    val slots: JSONObject?,
    val conversationHistory: List<InterventionMessage>
) {
    fun toJson(): String =
        JSONObject()
            .put("sessionId", sessionId)
            .put("message", message)
            .put("conversationHistory", JSONArray().apply {
                conversationHistory.forEach { put(it.toJson()) }
            })
            .apply {
                alert?.let { put("alert", it.toJson()) }
                slots?.let { put("slots", it) }
            }
            .toString()
}

data class InterventionChatResult(
    val assistantMessage: String,
    val slots: JSONObject?,
    val handoffReady: Boolean,
    val missingSlots: List<String>,
    val summary: String?,
    val rawBody: String
)

data class HandoffRequest(
    val sessionId: String,
    val slots: JSONObject?,
    val conversationHistory: List<InterventionMessage>
) {
    fun toJson(): String =
        JSONObject()
            .put("sessionId", sessionId)
            .put("conversationHistory", JSONArray().apply {
                conversationHistory.forEach { put(it.toJson()) }
            })
            .apply {
                slots?.let { put("slots", it) }
            }
            .toString()
}

data class HandoffResult(
    val reportMarkdown: String,
    val missingSlots: List<String>,
    val rawBody: String
)

enum class HandoffJobState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}

data class HandoffJobSubmission(
    val jobId: String,
    val sessionId: String,
    val status: HandoffJobState,
    val rawBody: String
)

data class HandoffJobStatusResult(
    val jobId: String,
    val sessionId: String,
    val status: HandoffJobState,
    val result: HandoffResult?,
    val error: String?,
    val rawBody: String
)

class HttpStatusException(
    val statusCode: Int,
    message: String
) : IllegalStateException(message)

class ServerUploader {
    fun postWindow(url: String, payload: ServerWindowPayload): UploadResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val body = payload.toJson().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            UploadResult(
                success = code in 200..299,
                statusCode = code,
                message = response.take(200)
            )
        } finally {
            connection.disconnect()
        }
    }

    fun getPrediction(url: String): CravingPrediction {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${response.take(200)}")
            }

            parsePrediction(response)
        } finally {
            connection.disconnect()
        }
    }

    fun postInterventionChat(
        url: String,
        request: InterventionChatRequest,
        readTimeoutMs: Int = DEFAULT_INTERVENTION_READ_TIMEOUT_MS
    ): InterventionChatResult {
        val response = postJson(url, request.toJson(), readTimeoutMs = readTimeoutMs)
        return parseChatResult(response)
    }

    fun postInterventionHandoff(url: String, request: HandoffRequest): HandoffResult {
        val response = postJson(url, request.toJson())
        return parseHandoffResult(response)
    }

    fun postInterventionHandoffJob(url: String, request: HandoffRequest): HandoffJobSubmission {
        val response = postJson(
            url = url,
            body = request.toJson(),
            connectTimeoutMs = HANDOFF_JOB_NETWORK_TIMEOUT_MS,
            readTimeoutMs = HANDOFF_JOB_NETWORK_TIMEOUT_MS
        )
        return parseHandoffJobSubmission(response)
    }

    fun getInterventionHandoffJob(url: String): HandoffJobStatusResult {
        val response = requestJson(
            url = url,
            method = "GET",
            connectTimeoutMs = HANDOFF_JOB_NETWORK_TIMEOUT_MS,
            readTimeoutMs = HANDOFF_JOB_NETWORK_TIMEOUT_MS
        )
        return parseHandoffJobStatus(response)
    }

    /**
     * 서버 예측 결과를 SSE 장기 연결로 수신한다.
     *
     * 서버는 `data: {"class": 0}` 형태의 이벤트를 여러 번 보낼 수 있다. 이 함수는
     * stream이 닫히거나 timeout될 때 반환하고, 재연결 정책은 ViewModel 쪽에서 관리한다.
     */
    fun listenPredictions(
        url: String,
        shouldContinue: () -> Boolean = { true },
        onPrediction: (CravingPrediction) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 15_000
            setRequestProperty("Accept", "text/event-stream, application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $code ${response.take(200)}")
            }

            val pendingDataLines = mutableListOf<String>()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (shouldContinue()) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isBlank() -> {
                            if (shouldContinue()) {
                                emitSseData(pendingDataLines, onPrediction)
                            }
                            pendingDataLines.clear()
                        }
                        line.startsWith("data:") -> pendingDataLines += line.removePrefix("data:").trimStart()
                        line.startsWith(":") -> Unit
                        line.startsWith("{") -> {
                            if (shouldContinue()) {
                                onPrediction(parsePrediction(line))
                            }
                        }
                    }
                }
            }
            if (shouldContinue()) {
                emitSseData(pendingDataLines, onPrediction)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun emitSseData(
        dataLines: List<String>,
        onPrediction: (CravingPrediction) -> Unit
    ) {
        val body = dataLines.joinToString(separator = "\n").trim()
        if (body.isBlank() || body == "[DONE]") return
        onPrediction(parsePrediction(body))
    }

    /**
     * 서버마다 class key 이름이 조금 다를 수 있어 허용 key를 넓게 둔다.
     * 워치에 보고할 최종 값은 반드시 정수 0/1/2여야 한다.
     */
    private fun parsePrediction(response: String): CravingPrediction {
        val json = JSONObject(response)
        val value = when {
            json.has("class") -> json.get("class")
            json.has("cravingClass") -> json.get("cravingClass")
            json.has("prediction") -> json.get("prediction")
            json.has("score") -> json.get("score")
            json.has("cravingScore") -> json.get("cravingScore")
            else -> throw IllegalArgumentException("prediction class key missing")
        }
        val cravingClass = value.toPredictionClass()
        require(cravingClass in 0..2) { "prediction class must be 0, 1, or 2" }

        return CravingPrediction(
            cravingClass = cravingClass,
            timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
            rawBody = response,
            score = json.optScore(),
            confidence = json.optFloatOrNull("confidence"),
            sequence = json.optLongOrNull("sequence"),
            uploadSentAtMs = json.optLongOrNull("sentAtMs")
                ?: json.optLongOrNull("uploadSentAtMs")
                ?: json.optLongOrNull("requestSentAtMs"),
            hasServerTimestamp = json.has("timestampMs"),
            sessionId = json.optCleanString("sessionId"),
            alert = json.optAlertMetadata()
        )
    }

    private fun postJson(
        url: String,
        body: String,
        connectTimeoutMs: Int = DEFAULT_INTERVENTION_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_INTERVENTION_READ_TIMEOUT_MS
    ): String = requestJson(
        url = url,
        method = "POST",
        body = body,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs
    )

    private fun requestJson(
        url: String,
        method: String,
        body: String? = null,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = body != null
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            body?.let { requestBody ->
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw HttpStatusException(code, "HTTP $code")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseChatResult(response: String): InterventionChatResult {
        val json = JSONObject(response)
        val handoff = json.optJSONObject("handoff")
        val readiness = json.optJSONObject("handoffReadiness")
        val assistantMessage =
            json.optCleanString("assistantResponse")
                ?: json.optCleanString("assistantMessage")
                ?: json.optCleanString("response")
                ?: json.optCleanString("message")
                ?: json.optCleanString("content")
                ?: handoff?.optCleanString("assistantResponse")
                ?: ""

        return InterventionChatResult(
            assistantMessage = assistantMessage,
            slots = json.optJSONObject("slots") ?: json.optJSONObject("mergedSlots"),
            handoffReady = json.optBoolean("handoffReady", false) ||
                readiness?.optBoolean("ready", false) == true ||
                handoff?.optBoolean("ready", false) == true,
            missingSlots = json.optStringList("missingSlots")
                .ifEmpty { readiness?.optStringList("missingSlots").orEmpty() }
                .ifEmpty { handoff?.optStringList("missingSlots").orEmpty() },
            summary = json.optCleanString("summary")
                ?: readiness?.optCleanString("summary")
                ?: handoff?.optCleanString("summary"),
            rawBody = response
        )
    }

    internal fun parseHandoffResult(response: String): HandoffResult {
        val json = JSONObject(response)
        return HandoffResult(
            reportMarkdown = json.optCleanString("reportMarkdown")
                ?: json.optCleanString("markdown")
                ?: json.optCleanString("report")
                ?: json.optCleanString("content")
                ?: response,
            missingSlots = json.optStringList("missingSlots"),
            rawBody = response
        )
    }

    internal fun parseHandoffJobSubmission(response: String): HandoffJobSubmission {
        val json = JSONObject(response)
        return HandoffJobSubmission(
            jobId = json.requireCleanString("jobId"),
            sessionId = json.requireCleanString("sessionId"),
            status = json.requireHandoffJobState(),
            rawBody = response
        )
    }

    internal fun parseHandoffJobStatus(response: String): HandoffJobStatusResult {
        val json = JSONObject(response)
        val status = json.requireHandoffJobState()
        val resultObject = json.optJSONObject("result")
        val result = if (status == HandoffJobState.COMPLETED && resultObject != null) {
            parseHandoffResult(resultObject.toString())
        } else {
            null
        }
        return HandoffJobStatusResult(
            jobId = json.requireCleanString("jobId"),
            sessionId = json.requireCleanString("sessionId"),
            status = status,
            result = result,
            error = json.optCleanString("error"),
            rawBody = response
        )
    }

    private fun JSONObject.requireHandoffJobState(): HandoffJobState {
        val value = requireCleanString("status").uppercase()
        return runCatching { HandoffJobState.valueOf(value) }
            .getOrElse { throw IllegalArgumentException("unknown handoff job status: $value") }
    }

    private fun JSONObject.requireCleanString(key: String): String =
        optCleanString(key) ?: throw IllegalArgumentException("$key missing")

    private fun Any.toPredictionClass(): Int {
        val number = when (this) {
            is Number -> toDouble()
            is String -> toDoubleOrNull()
            else -> null
        } ?: throw IllegalArgumentException("prediction class is not numeric")

        val intValue = number.toInt()
        if (number != intValue.toDouble()) {
            throw IllegalArgumentException("prediction class must be an integer")
        }
        return intValue
    }

    private fun JSONObject.optScore(): Float? {
        val scoreValue = when {
            has("score") -> get("score")
            has("cravingScore") -> get("cravingScore")
            else -> return null
        }
        return when (scoreValue) {
            is Number -> scoreValue.toFloat()
            is String -> scoreValue.toFloatOrNull()
            else -> null
        }
    }

    private fun JSONObject.optAlertMetadata(): AlertMetadata {
        val alertObject = optJSONObject("alert")
        val isPresent = ALERT_METADATA_KEYS.any(::has) || has("alert")
        val level = optCleanString("alertLevel")
            ?: alertObject?.optCleanString("alertLevel")
            ?: "none"
        val action = optCleanString("alertAction") ?: alertObject?.optCleanString("alertAction")
        val windowMean = optFloatOrNull("windowMean") ?: alertObject?.optFloatOrNull("windowMean")
        val triggerReason = optCleanString("triggerReason") ?: alertObject?.optCleanString("triggerReason")
        val required = when {
            has("alertRequired") -> optBoolean("alertRequired", false)
            alertObject?.has("alertRequired") == true -> alertObject.optBoolean("alertRequired", false)
            else -> false
        }
        return AlertMetadata(
            alertLevel = level,
            alertAction = action,
            windowMean = windowMean,
            triggerReason = triggerReason,
            alertRequired = required,
            isPresent = isPresent
        )
    }

    private fun JSONObject.optFloatOrNull(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        val value = get(key)
        return when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = get(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.optCleanString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        if (!has(key) || isNull(key)) return emptyList()
        val value = get(key)
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                value.optString(index).trim().takeIf { it.isNotBlank() }
            }
            is String -> value.split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }
            else -> emptyList()
        }
    }

    companion object {
        private const val DEFAULT_INTERVENTION_CONNECT_TIMEOUT_MS = 8_000
        private const val DEFAULT_INTERVENTION_READ_TIMEOUT_MS = 20_000
        private const val HANDOFF_JOB_NETWORK_TIMEOUT_MS = 5_000
        private val ALERT_METADATA_KEYS = listOf(
            "alertLevel",
            "alertAction",
            "windowMean",
            "triggerReason",
            "alertRequired"
        )
    }
}
