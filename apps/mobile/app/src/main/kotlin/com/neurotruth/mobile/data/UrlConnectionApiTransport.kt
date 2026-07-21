package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.ApiResponse
import com.neurotruth.mobile.core.net.ApiTransport
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only place that performs real HTTP.
 *
 * Everything above it is written against [ApiTransport], which is why the auth and payload
 * contracts are testable without an emulator.
 */
class UrlConnectionApiTransport(
    private val defaultConnectTimeoutMs: Int = 8_000,
    private val defaultReadTimeoutMs: Int = 20_000,
) : ApiTransport {

    override fun execute(request: ApiRequest): ApiResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = request.connectTimeoutMs ?: defaultConnectTimeoutMs
            readTimeout = request.readTimeoutMs ?: defaultReadTimeoutMs
            instanceFollowRedirects = false
            request.headers.forEach(::setRequestProperty)
        }

        return try {
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            ApiResponse(status, text)
        } catch (error: Exception) {
            // A transport failure is reported as -1 so UploadFailurePolicy can treat it as
            // retryable rather than as a terminal 4xx.
            ApiResponse(-1, error.message.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
