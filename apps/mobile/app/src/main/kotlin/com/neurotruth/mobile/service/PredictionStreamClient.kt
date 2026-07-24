package com.neurotruth.mobile.service

import android.util.Log
import com.neurotruth.mobile.core.CravingPrediction
import com.neurotruth.mobile.core.net.ApiEndpoints
import com.neurotruth.mobile.core.net.AuthenticatedApiClient
import com.neurotruth.mobile.core.net.AuthenticationRequiredException
import com.neurotruth.mobile.core.net.HttpStatusException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Frame-level parsing for `text/event-stream`.
 *
 * A stream carries three things this app must tell apart: `data:` payloads, `:` heartbeat comments
 * that keep the connection warm, and the `[DONE]` sentinel that ends it. Treating a heartbeat as a
 * payload would log a parse failure every few seconds; treating `[DONE]` as a payload would attempt
 * to parse the literal string as a prediction.
 *
 * Kept free of Android and network types so it is covered by JVM unit tests.
 */
object SseFrameParser {
    const val DATA_PREFIX: String = "data:"
    const val COMMENT_PREFIX: String = ":"
    const val DONE_SENTINEL: String = "[DONE]"

    /** The payload of a `data:` line, or `null` for a comment, sentinel, blank or other field. */
    fun payloadOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(COMMENT_PREFIX) && !trimmed.startsWith(DATA_PREFIX)) return null
        if (!trimmed.startsWith(DATA_PREFIX)) return null
        val payload = trimmed.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty() || payload == DONE_SENTINEL) return null
        return payload
    }

    fun isDone(line: String): Boolean =
        line.trim().removePrefix(DATA_PREFIX).trim() == DONE_SENTINEL
}

/**
 * Holds the SSE connection to `GET /api/predictions/stream`.
 *
 * The 401 path is the delicate part: [AuthenticatedApiClient.executeStreaming] can only refresh if
 * the streaming block throws [HttpStatusException] with 401, so the read loop raises one both when
 * the initial response is 401 and when the server closes an established stream with one. Returning
 * normally would leave the service silently disconnected with an expired token.
 *
 * The upload response and this stream can deliver the same prediction, so both are deduplicated by
 * id against the same ledger before anything reaches the user.
 */
class PredictionStreamClient(
    private val apiClient: AuthenticatedApiClient,
    private val endpoints: ApiEndpoints,
    private val ledger: PredictionLedger,
    private val listener: Listener,
    private val scope: CoroutineScope,
) {

    interface Listener {
        fun onPrediction(prediction: CravingPrediction)
        fun onAuthenticationRequired()
    }

    @Volatile
    private var job: Job? = null

    @Volatile
    private var connection: HttpURLConnection? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive) {
                try {
                    apiClient.executeStreaming { accessToken -> readStream(accessToken) }
                    backoffMs = INITIAL_BACKOFF_MS
                } catch (error: AuthenticationRequiredException) {
                    listener.onAuthenticationRequired()
                    return@launch
                } catch (error: Exception) {
                    Log.w(TAG, "예측 스트림 끊김: ${error.message}")
                }
                if (!isActive) return@launch
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { connection?.disconnect() }
        connection = null
    }

    private fun readStream(accessToken: String) {
        val open = (URL(endpoints.predictionStream).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = CONNECT_TIMEOUT_MS
            // Bounded a little above the server heartbeat interval, not infinite: a silently dropped
            // connection (NAT rebind, Wi-Fi↔cellular handoff, doze) surfaces as a read timeout
            // rather than parking readLine() forever, so the reconnect/backoff loop can self-heal.
            // A blocking read cannot be interrupted by coroutine cancellation, which is why 0 here
            // would strand the socket until the whole service was destroyed.
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
        }
        connection = open

        try {
            val status = open.responseCode
            if (status == HTTP_UNAUTHORIZED) {
                throw HttpStatusException(HTTP_UNAUTHORIZED, "prediction stream rejected the token")
            }
            if (status !in 200..299) {
                throw HttpStatusException(status, "prediction stream failed with HTTP $status")
            }

            open.inputStream.bufferedReader().use { reader -> consume(reader) }
        } finally {
            runCatching { open.disconnect() }
            if (connection === open) connection = null
        }
    }

    private fun consume(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: return
            if (SseFrameParser.isDone(line)) return
            val payload = SseFrameParser.payloadOf(line) ?: continue

            // A mid-stream 401 arrives as a frame rather than a status line. It has to become an
            // HttpStatusException here, inside the read loop, for executeStreaming to refresh.
            if (statusOf(payload) == HTTP_UNAUTHORIZED) {
                throw HttpStatusException(HTTP_UNAUTHORIZED, "prediction stream token expired")
            }

            val prediction = PredictionPayloadParser.parse(payload) ?: continue
            if (!ledger.claimPrediction(prediction)) continue
            listener.onPrediction(prediction)
        }
    }

    private fun statusOf(payload: String): Int? = runCatching {
        org.json.JSONObject(payload).optInt("status", 0).takeIf { it > 0 }
    }.getOrNull()

    private companion object {
        const val TAG = "PredictionStream"
        const val HTTP_UNAUTHORIZED = 401
        const val CONNECT_TIMEOUT_MS = 8_000

        /** Above the server heartbeat cadence, so a live stream never trips it but a dead one does. */
        const val READ_TIMEOUT_MS = 45_000

        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
