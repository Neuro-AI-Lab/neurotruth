package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.net.ApiEndpoints
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.AuthenticatedApiClient
import com.neurotruth.mobile.core.net.HttpStatusException
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * `POST /api/sessions/{sessionId}/transcriptions`.
 *
 * Multipart cannot be modelled as [com.neurotruth.mobile.core.net.ApiRequest], so it goes through
 * [AuthenticatedApiClient.executeStreaming] instead — a 401 is raised as [HttpStatusException] so
 * the single refresh-and-replay contract still owns it and this class never handles auth itself.
 *
 * Audio is uploaded and discarded: the caller deletes the temporary file on every outcome, and the
 * server keeps only the returned transcript, which the user must confirm before it is ever sent.
 */
class TranscriptionRepository(
    private val client: AuthenticatedApiClient,
    private val endpoints: ApiEndpoints,
) {

    /** Returns the recognized text, or null when the server produced nothing usable. */
    fun transcribe(sessionId: String, audio: File, language: String = LANGUAGE_KO): String? {
        require(audio.exists() && audio.length() > 0L) { "audio file must not be empty" }
        val url = endpoints.transcriptions(sessionId)
        val body = client.executeStreaming { accessToken -> upload(url, accessToken, audio, language) }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optString("text").takeIf { it.isNotBlank() }
    }

    private fun upload(url: String, accessToken: String, audio: File, language: String): String {
        val boundary = "----neurotruth${System.currentTimeMillis()}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(
                partLength(boundary, language) + audio.length() + tailLength(boundary),
            )
        }

        return try {
            DataOutputStream(connection.outputStream).use { output ->
                output.writeBytes(textPart(boundary, language))
                // The server accepts `.m4a`; the on-disk name is a cache detail and is not leaked.
                output.writeBytes(filePartHeader(boundary, AUDIO_FILE_NAME))
                audio.inputStream().use { it.copyTo(output) }
                output.writeBytes(tail(boundary))
                output.flush()
            }
            val status = connection.responseCode
            val stream =
                if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            when {
                status == HTTP_UNAUTHORIZED -> throw HttpStatusException(status, "unauthorized")
                status !in 200..299 -> throw ApiHttpException(status, text)
                else -> text
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun textPart(boundary: String, language: String): String =
        "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"language\"\r\n\r\n" +
            "$language\r\n"

    private fun filePartHeader(boundary: String, fileName: String): String =
        "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"audio\"; filename=\"$fileName\"\r\n" +
            "Content-Type: audio/mp4\r\n\r\n"

    private fun tail(boundary: String): String = "\r\n--$boundary--\r\n"

    private fun partLength(boundary: String, language: String): Long =
        (textPart(boundary, language) + filePartHeader(boundary, AUDIO_FILE_NAME))
            .toByteArray(Charsets.UTF_8).size.toLong()

    private fun tailLength(boundary: String): Long =
        tail(boundary).toByteArray(Charsets.UTF_8).size.toLong()

    companion object {
        const val LANGUAGE_KO: String = "ko"
        const val AUDIO_FILE_NAME: String = "audio.m4a"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val HTTP_UNAUTHORIZED = 401
    }
}
