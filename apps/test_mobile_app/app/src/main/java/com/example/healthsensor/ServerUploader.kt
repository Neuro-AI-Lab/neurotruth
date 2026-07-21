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
    val clientWindowId: String,
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
        root.put("clientWindowId", clientWindowId)
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

enum class UploadFailureAction {
    RETRY_SAME_PAYLOAD,
    DROP_AND_CONTINUE,
    PAUSE_AND_DROP,
    AUTHENTICATION_REQUIRED
}

object UploadFailurePolicy {
    fun resolve(statusCode: Int): UploadFailureAction =
        when {
            statusCode == 401 -> UploadFailureAction.AUTHENTICATION_REQUIRED
            statusCode == 409 -> UploadFailureAction.DROP_AND_CONTINUE
            statusCode == 408 || statusCode == 429 -> UploadFailureAction.RETRY_SAME_PAYLOAD
            statusCode in 400..499 -> UploadFailureAction.PAUSE_AND_DROP
            else -> UploadFailureAction.RETRY_SAME_PAYLOAD
        }
}

data class AlertMetadata(
    val alertLevel: String = "none",
    val alertAction: String? = null,
    val windowMean: Float? = null,
    val classOneRatio: Float? = null,
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
                classOneRatio?.let { put("classOneRatio", it.toDouble()) }
                triggerReason?.let { put("triggerReason", it) }
            }
}

data class CravingPrediction(
    val cravingClass: Int,
    val timestampMs: Long,
    val rawBody: String,
    val score: Float? = null,
    val confidence: Float? = null,
    val predictionSchema: String = BINARY_PREDICTION_SCHEMA,
    val classCode: String? = null,
    val cravingProbability: Float? = null,
    val classProbabilities: Map<String, Float> = emptyMap(),
    val predictionId: String? = null,
    val sequence: Long? = null,
    val uploadSentAtMs: Long? = null,
    val hasServerTimestamp: Boolean = false,
    val sessionId: String? = null,
    val alertId: String? = null,
    val source: String? = null,
    val alert: AlertMetadata = AlertMetadata()
) {
    val label: String
        get() = when (cravingClass) {
            0 -> "0 낮음"
            1 -> "1 높음"
            else -> "$cravingClass 알 수 없음"
        }

    companion object {
        const val BINARY_PREDICTION_SCHEMA = "binary-craving-v1"
    }
}

class HttpStatusException(
    val statusCode: Int,
    message: String
) : IllegalStateException(message)

class ServerUploader {
    fun postWindowAuthenticated(
        client: AuthenticatedApiClient,
        url: String,
        payload: ServerWindowPayload
    ): UploadResult {
        val response = client.executeAuthenticated(
            ApiRequest(
                method = "POST",
                url = url,
                headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                body = payload.toJson()
            )
        )
        return UploadResult(
            success = response.statusCode in 200..299,
            statusCode = response.statusCode,
            message = response.body.take(200)
        )
    }

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

    /**
     * 서버 예측 결과를 SSE 장기 연결로 수신한다.
     *
     * 서버는 `data: {"class": 0}` 형태의 이벤트를 여러 번 보낼 수 있다. 이 함수는
     * stream이 닫히거나 timeout될 때 반환하고, 재연결 정책은 ViewModel 쪽에서 관리한다.
     */
    fun listenPredictions(
        url: String,
        accessToken: String? = null,
        shouldContinue: () -> Boolean = { true },
        onPrediction: (CravingPrediction) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 15_000
            setRequestProperty("Accept", "text/event-stream, application/json")
            setRequestProperty("Cache-Control", "no-cache")
            accessToken?.takeIf(String::isNotBlank)?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.errorStream?.close()
                throw HttpStatusException(code, "HTTP $code")
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

    /** Binary prediction events are fail-closed so legacy class 2 cannot reach the UI. */
    internal fun parsePrediction(response: String): CravingPrediction {
        val json = JSONObject(response)
        require(json.optString("predictionSchema") == CravingPrediction.BINARY_PREDICTION_SCHEMA) {
            "unsupported prediction schema"
        }
        val value = when {
            json.has("class") -> json.get("class")
            json.has("cravingClass") -> json.get("cravingClass")
            json.has("prediction") -> json.get("prediction")
            json.has("score") -> json.get("score")
            json.has("cravingScore") -> json.get("cravingScore")
            else -> throw IllegalArgumentException("prediction class key missing")
        }
        val cravingClass = value.toPredictionClass()
        require(cravingClass in 0..1) { "prediction class must be 0 or 1" }

        val probabilities = json.optJSONObject("classProbabilities")
            ?: throw IllegalArgumentException("classProbabilities missing")
        val lowProbability = probabilities.requiredProbability("low")
        val highProbability = probabilities.requiredProbability("high")
        require(kotlin.math.abs(lowProbability + highProbability - 1f) <= 0.001f) {
            "class probabilities must sum to one"
        }
        val cravingProbability = json.requiredProbability("cravingProbability")
        require(kotlin.math.abs(cravingProbability - highProbability) <= 0.000001f) {
            "cravingProbability must equal class 1 probability"
        }

        return CravingPrediction(
            cravingClass = cravingClass,
            timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
            rawBody = response,
            score = json.optScore(),
            confidence = json.requiredProbability("confidence"),
            predictionSchema = CravingPrediction.BINARY_PREDICTION_SCHEMA,
            classCode = json.optCleanString("classCode"),
            cravingProbability = cravingProbability,
            classProbabilities = mapOf("low" to lowProbability, "high" to highProbability),
            predictionId = json.optCleanString("predictionId"),
            sequence = json.optLongOrNull("sequence"),
            uploadSentAtMs = json.optLongOrNull("sentAtMs")
                ?: json.optLongOrNull("uploadSentAtMs")
                ?: json.optLongOrNull("requestSentAtMs"),
            hasServerTimestamp = json.has("timestampMs"),
            sessionId = json.optCleanString("sessionId"),
            alertId = json.optCleanString("alertId"),
            source = json.optCleanString("source") ?: "watch_sensor",
            alert = json.optAlertMetadata()
        )
    }

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
        val classOneRatio = optFloatOrNull("classOneRatio") ?: alertObject?.optFloatOrNull("classOneRatio")
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
            classOneRatio = classOneRatio,
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

    private fun JSONObject.requiredProbability(key: String): Float {
        val value = optFloatOrNull(key) ?: throw IllegalArgumentException("$key missing")
        require(value.isFinite() && value in 0f..1f) { "$key must be between zero and one" }
        return value
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

    companion object {
        private val ALERT_METADATA_KEYS = listOf(
            "alertLevel",
            "alertAction",
            "windowMean",
            "classOneRatio",
            "triggerReason",
            "alertRequired"
        )
    }
}
