package com.example.healthsensor

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

interface RppgGateway {
    fun getStatus(): RppgServiceStatus
    fun createJob(
        video: File,
        clientCaptureId: String,
        capturedAtMs: Long,
        durationMs: Long,
        sessionId: String? = null
    ): RppgAcceptedJob
    fun getJob(jobId: String): RppgJobResult
    fun retryJob(jobId: String): RppgAcceptedJob
}

class RppgApiClient(
    private val authenticatedClient: AuthenticatedApiClient
) : RppgGateway {
    override fun getStatus(): RppgServiceStatus {
        val response = authenticatedClient.executeAuthenticated(
            ApiRequest("GET", authenticatedClient.endpoints.rppgStatus)
        )
        response.requireSuccess()
        return RppgServiceStatus.parse(response.body)
    }

    override fun createJob(
        video: File,
        clientCaptureId: String,
        capturedAtMs: Long,
        durationMs: Long,
        sessionId: String?
    ): RppgAcceptedJob {
        require(video.isFile) { "video missing" }
        require(durationMs in 19_500L..20_500L) { "durationMs out of range" }
        UUID.fromString(clientCaptureId)
        sessionId?.let(UUID::fromString)

        return authenticatedClient.executeAuthenticatedStream { token ->
            val response = executeMultipart(
                url = authenticatedClient.endpoints.rppgJobs,
                token = token,
                video = video,
                fields = linkedMapOf(
                    "clientCaptureId" to clientCaptureId,
                    "capturedAtMs" to capturedAtMs.toString(),
                    "durationMs" to durationMs.toString()
                ).apply { sessionId?.let { put("sessionId", it) } }
            )
            if (response.statusCode == 401) throw HttpStatusException(401, "authentication required")
            if (response.statusCode != 202) throw ApiHttpException(response.statusCode)
            RppgAcceptedJob.parse(response.body)
        }
    }

    override fun getJob(jobId: String): RppgJobResult {
        val response = authenticatedClient.executeAuthenticated(
            ApiRequest("GET", authenticatedClient.endpoints.rppgJob(jobId))
        )
        response.requireSuccess()
        return RppgJobResult.parse(response.body)
    }

    override fun retryJob(jobId: String): RppgAcceptedJob {
        val response = authenticatedClient.executeAuthenticated(
            ApiRequest("POST", authenticatedClient.endpoints.retryRppgJob(jobId))
        )
        if (response.statusCode != 202) throw ApiHttpException(response.statusCode)
        return RppgAcceptedJob.parse(response.body)
    }

    private fun executeMultipart(
        url: String,
        token: String,
        video: File,
        fields: Map<String, String>
    ): ApiResponse {
        val boundary = "----NeuroTruth-${UUID.randomUUID()}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_TIMEOUT_MS
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                fields.forEach { (name, value) ->
                    output.writeUtf8("--$boundary\r\n")
                    output.writeUtf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    output.writeUtf8(value)
                    output.writeUtf8("\r\n")
                }
                output.writeUtf8("--$boundary\r\n")
                output.writeUtf8("Content-Disposition: form-data; name=\"video\"; filename=\"capture.mp4\"\r\n")
                output.writeUtf8("Content-Type: video/mp4\r\n\r\n")
                video.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                output.writeUtf8("\r\n--$boundary--\r\n")
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ApiResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun BufferedOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }

    private fun ApiResponse.requireSuccess() {
        if (statusCode !in 200..299) throw ApiHttpException(statusCode)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val UPLOAD_TIMEOUT_MS = 180_000
    }
}
