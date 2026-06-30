package com.example.healthsensor

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

data class CravingPrediction(
    val cravingClass: Int,
    val timestampMs: Long,
    val rawBody: String,
    val score: Float? = null
) {
    val label: String
        get() = when (cravingClass) {
            0 -> "0 낮음"
            1 -> "1 중간"
            2 -> "2 높음"
            else -> "$cravingClass 알 수 없음"
        }
}

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
            score = json.optScore()
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
}
