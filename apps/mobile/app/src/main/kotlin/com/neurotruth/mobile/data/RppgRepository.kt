package com.neurotruth.mobile.data

import android.content.Context
import com.neurotruth.mobile.core.CravingPrediction
import com.neurotruth.mobile.core.RppgContract
import com.neurotruth.mobile.core.RppgJobStatus
import com.neurotruth.mobile.core.RppgRouteOnce
import com.neurotruth.mobile.core.RppgRouteReceiptStore
import com.neurotruth.mobile.core.SOURCE_CAMERA
import com.neurotruth.mobile.core.net.ApiEndpoints
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.AuthenticationRequiredException
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.ApiResponse
import com.neurotruth.mobile.core.net.AuthenticatedApiClient
import com.neurotruth.mobile.core.net.HttpStatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** `GET /api/rppg/status`. Only all three flags together are ever read as ready. */
data class RppgServiceStatus(
    val enabled: Boolean,
    val available: Boolean,
    val modelLoaded: Boolean,
) {
    val ready: Boolean get() = enabled && available && modelLoaded

    companion object {
        /**
         * `ready` is honoured only as a fallback for the two detail flags. It never substitutes for
         * `enabled`: a server that has the model loaded but the feature switched off is not ready.
         */
        fun parse(body: String): RppgServiceStatus = fromJson(JSONObject(body))

        fun fromJson(root: JSONObject): RppgServiceStatus {
            val ready = root.optBoolean("ready", false)
            return RppgServiceStatus(
                enabled = root.optBoolean("enabled", false),
                available = if (root.has("available")) root.optBoolean("available", false) else ready,
                modelLoaded = if (root.has("modelLoaded")) {
                    root.optBoolean("modelLoaded", false)
                } else {
                    ready
                },
            )
        }
    }
}

/** The 202 body of `POST /api/rppg/jobs` and `POST /api/rppg/jobs/{id}/retry`. */
data class RppgAcceptedJob(
    val jobId: String,
    val captureId: String,
    val status: String,
) {
    companion object {
        fun parse(body: String): RppgAcceptedJob {
            val root = JSONObject(body)
            return RppgAcceptedJob(
                jobId = root.requiredString("jobId"),
                captureId = root.requiredString("captureId"),
                status = root.optString("status").ifBlank { RppgJobStatus.QUEUED.wireValue },
            )
        }
    }
}

/**
 * `GET /api/rppg/jobs/{jobId}`.
 *
 * An unknown status string parses to `null` rather than to a terminal value: treating an unmapped
 * status as terminal would abandon a job that is in fact still running.
 */
data class RppgJobSnapshot(
    val jobId: String,
    val captureId: String,
    val status: RppgJobStatus?,
    val capturedAtMs: Long,
    val cravingProbability: Float?,
    val heartRateBpm: Float? = null,
    val qualityScore: Float? = null,
    val predictionId: String? = null,
    val alertId: String? = null,
    val alertAction: String? = null,
    val failureCode: String? = null,
    val retryAllowed: Boolean = false,
) {
    val isTerminal: Boolean get() = status?.isTerminal == true

    /**
     * The Home/dashboard prediction for this capture.
     *
     * `source` is always `camera_rppg`, which is what lets [com.neurotruth.mobile.core
     * .LatestPredictionPolicy] keep a newer Watch result in front of it.
     */
    fun toPrediction(): CravingPrediction? {
        if (status != RppgJobStatus.COMPLETED) return null
        val probability = cravingProbability?.takeIf { it.isFinite() && it in 0f..1f } ?: return null
        return CravingPrediction(
            cravingProbability = probability,
            timestampMs = capturedAtMs,
            source = SOURCE_CAMERA,
            predictionId = predictionId,
            alertId = alertId,
            alertAction = alertAction,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("jobId", jobId)
        .put("captureId", captureId)
        .putNullable("status", status?.wireValue)
        .put("capturedAtMs", capturedAtMs)
        .putNullable("cravingProbability", cravingProbability?.toDouble())
        .putNullable("heartRateBpm", heartRateBpm?.toDouble())
        .putNullable("qualityScore", qualityScore?.toDouble())
        .putNullable("predictionId", predictionId)
        .putNullable("alertId", alertId)
        .putNullable("alertAction", alertAction)
        .putNullable("failureCode", failureCode)
        .put("retryAllowed", retryAllowed)

    companion object {
        fun parse(body: String): RppgJobSnapshot = fromJson(JSONObject(body))

        fun fromJson(root: JSONObject): RppgJobSnapshot {
            val prediction = root.optJSONObject("prediction")
            val alert = root.optJSONObject("alert") ?: prediction?.optJSONObject("alert")
            return RppgJobSnapshot(
                jobId = root.requiredString("jobId"),
                captureId = root.optString("captureId").ifBlank { root.requiredString("jobId") },
                status = RppgJobStatus.fromWire(root.optString("status").trim()),
                capturedAtMs = root.optLong("capturedAtMs", 0L),
                cravingProbability = resolveProbability(root, prediction),
                heartRateBpm = root.optFloatOrNull("heartRateBpm"),
                qualityScore = root.optFloatOrNull("qualityScore"),
                predictionId = root.optNullableString("predictionId")
                    ?: prediction?.optNullableString("predictionId"),
                alertId = root.optNullableString("alertId") ?: alert?.optNullableString("alertId"),
                alertAction = root.optNullableString("alertAction")
                    ?: alert?.optNullableString("alertAction"),
                failureCode = root.optNullableString("failureCode"),
                retryAllowed = root.optBoolean("retryAllowed", false),
            )
        }

        /**
         * `classIndex` + `confidence` is the binary classifier's own framing. It is folded into a
         * single probability of class 1 here so nothing downstream ever bands on `classIndex`.
         */
        private fun resolveProbability(root: JSONObject, prediction: JSONObject?): Float? {
            root.optFloatOrNull("cravingProbability")?.let { return it }
            prediction?.optFloatOrNull("cravingProbability")?.let { return it }
            val classIndex = root.optIntOrNull("classIndex")
                ?: prediction?.optIntOrNull("classIndex")
                ?: return null
            val confidence = root.optFloatOrNull("confidence")
                ?: prediction?.optFloatOrNull("confidence")
                ?: return null
            return when (classIndex) {
                0 -> 1f - confidence
                1 -> confidence
                else -> null
            }?.takeIf { it in 0f..1f }
        }
    }
}

/** What survives a process death: the ids needed to pick the job back up. Never the video. */
data class RppgPendingJob(
    val ownerUserId: String,
    val jobId: String,
    val captureId: String,
    val clientCaptureId: String,
)

/**
 * The durable side of NT-04R.
 *
 * Pending ids are opaque identifiers and live in ordinary preferences alongside the active session
 * id. The analysed result is not — it carries a craving probability — so it goes through the
 * Keystore-backed store, the same as the pending chat body.
 */
class RppgJobStore(context: Context) : RppgRouteReceiptStore {
    private val preferences =
        context.applicationContext.getSharedPreferences("neurotruth_rppg", Context.MODE_PRIVATE)
    private val secure = KeystoreSecureStore(context, "neurotruth_rppg_result", "neurotruth_rppg_v1")

    fun savePending(value: RppgPendingJob): Boolean = preferences.edit()
        .putString(KEY_OWNER, value.ownerUserId)
        .putString(KEY_JOB_ID, value.jobId)
        .putString(KEY_CAPTURE_ID, value.captureId)
        .putString(KEY_CLIENT_CAPTURE_ID, value.clientCaptureId)
        .commit()

    fun loadPending(): RppgPendingJob? {
        val owner = preferences.getString(KEY_OWNER, null) ?: return null
        val jobId = preferences.getString(KEY_JOB_ID, null) ?: return null
        val captureId = preferences.getString(KEY_CAPTURE_ID, null) ?: return null
        val clientCaptureId = preferences.getString(KEY_CLIENT_CAPTURE_ID, null) ?: return null
        return RppgPendingJob(owner, jobId, captureId, clientCaptureId)
    }

    fun clearPending(): Boolean = preferences.edit()
        .remove(KEY_OWNER)
        .remove(KEY_JOB_ID)
        .remove(KEY_CAPTURE_ID)
        .remove(KEY_CLIENT_CAPTURE_ID)
        .commit()

    fun saveResult(ownerUserId: String, snapshot: RppgJobSnapshot): Boolean =
        secure.putString(KEY_RESULT, snapshot.toJson().put("ownerUserId", ownerUserId).toString())

    fun loadResult(ownerUserId: String): RppgJobSnapshot? {
        val encoded = secure.getString(KEY_RESULT) ?: return null
        return runCatching {
            val root = JSONObject(encoded)
            if (root.optString("ownerUserId") != ownerUserId) return null
            RppgJobSnapshot.fromJson(root)
        }.getOrNull()
    }

    override fun wasRouted(ownerUserId: String, jobId: String): Boolean =
        preferences.getString(routeKey(ownerUserId), null) == jobId

    override fun markRouted(ownerUserId: String, jobId: String): Boolean =
        preferences.edit().putString(routeKey(ownerUserId), jobId).commit()

    private fun routeKey(ownerUserId: String) = "routed_$ownerUserId"

    private companion object {
        const val KEY_OWNER = "pending_owner"
        const val KEY_JOB_ID = "pending_job_id"
        const val KEY_CAPTURE_ID = "pending_capture_id"
        const val KEY_CLIENT_CAPTURE_ID = "pending_client_capture_id"
        const val KEY_RESULT = "latest_result"
    }
}

/**
 * The two-speed polling schedule of NT-04R, kept pure so the ceiling is unit-tested.
 *
 * Before the ceiling the app polls on the 2-second foreground tick. After it, the spinner is
 * dismissed and the job drops to a 30-second background check — a job that never leaves `running`
 * must not hold a spinner or a 2-second timer open forever.
 */
object RppgPollPlan {
    fun isSlow(elapsedMs: Long): Boolean = elapsedMs >= RppgContract.POLL_CEILING_MS

    fun intervalMs(elapsedMs: Long): Long = if (isSlow(elapsedMs)) {
        RppgContract.BACKGROUND_POLL_INTERVAL_MS
    } else {
        RppgContract.POLL_INTERVAL_MS
    }

    fun notice(elapsedMs: Long): String? =
        if (isSlow(elapsedMs)) RppgContract.SLOW_ANALYSIS_NOTICE else null
}

/** Only transport and server-side failures are eligible for background polling. */
internal fun shouldRetryRppgPolling(error: Throwable): Boolean = when (error) {
    is AuthenticationRequiredException -> false
    is ApiHttpException -> error.statusCode >= 500
    else -> true
}

/** Progress reported while a job is still `queued` or `running`. */
data class RppgPollProgress(val elapsedMs: Long, val slowNotice: String?)

class RppgUploadTooLargeException :
    RuntimeException("video exceeds ${RppgContract.MAX_UPLOAD_BYTES} bytes")

class RppgCaptureLengthException(val durationMs: Long) :
    RuntimeException("durationMs $durationMs outside the 20s capture contract")

/**
 * NT-04R's job lifecycle: readiness, upload, recovery, polling, and the route-once claim.
 *
 * The app calls only the NeuroTruth backend through [ApiEndpoints]; the analysis host is never
 * addressed from here and never appears in any copy this class produces.
 *
 * The single ordering rule that recovery depends on: the pending ids are committed to disk
 * **before** the local MP4 is deleted. Reversing the two loses the job on a crash in between, and
 * the video is already gone from the device.
 */
class RppgRepository(
    private val client: AuthenticatedApiClient,
    private val endpoints: ApiEndpoints,
    private val store: RppgJobStore,
    private val profile: ProfileRepository = ProfileRepository(client, endpoints),
) {
    private val routeOnce = RppgRouteOnce(store)

    /** The signed-in user, from memory when possible and from `GET /api/me` after a cold start. */
    fun ownerUserId(): String = client.user()?.id ?: profile.me().userId

    fun status(): RppgServiceStatus {
        val response = client.execute(ApiRequest("GET", endpoints.rppgStatus))
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        return RppgServiceStatus.parse(response.body)
    }

    fun isReady(): Boolean = runCatching { status().ready }.getOrDefault(false)

    fun pendingJob(): RppgPendingJob? = store.loadPending()

    fun latestResult(ownerUserId: String): RppgJobSnapshot? = store.loadResult(ownerUserId)

    /**
     * Uploads the capture, persists the recovery ids, and only then deletes the MP4.
     *
     * Only HTTP 202 is success. Any other status — including a 200 — is a contract violation and is
     * raised rather than silently treated as an accepted job.
     */
    fun submit(
        ownerUserId: String,
        video: File,
        clientCaptureId: String,
        capturedAtMs: Long,
        durationMs: Long,
        sessionId: String? = null,
    ): RppgAcceptedJob {
        require(video.isFile) { "capture file missing" }
        if (!RppgContract.isValidDuration(durationMs)) throw RppgCaptureLengthException(durationMs)
        if (video.length() > RppgContract.MAX_UPLOAD_BYTES) throw RppgUploadTooLargeException()
        UUID.fromString(clientCaptureId)
        sessionId?.let(UUID::fromString)

        val accepted = client.executeStreaming { token ->
            val response = postMultipart(
                url = endpoints.rppgJobs,
                token = token,
                video = video,
                fields = linkedMapOf(
                    "clientCaptureId" to clientCaptureId,
                    "capturedAtMs" to capturedAtMs.toString(),
                    "durationMs" to durationMs.toString(),
                ).apply { sessionId?.let { put("sessionId", it) } },
            )
            // Thrown from inside the block so the client can refresh and replay the whole upload.
            if (response.statusCode == AuthenticatedApiClient.HTTP_UNAUTHORIZED) {
                throw HttpStatusException(response.statusCode, "authentication required")
            }
            if (response.statusCode != HTTP_ACCEPTED) {
                throw ApiHttpException(response.statusCode, response.body)
            }
            RppgAcceptedJob.parse(response.body)
        }

        val persisted = store.savePending(
            RppgPendingJob(ownerUserId, accepted.jobId, accepted.captureId, clientCaptureId),
        )
        check(persisted) { "could not persist the accepted job" }
        video.delete()
        return accepted
    }

    fun job(jobId: String): RppgJobSnapshot {
        val response = client.execute(ApiRequest("GET", endpoints.rppgJob(jobId)))
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        return RppgJobSnapshot.parse(response.body)
    }

    /** Re-analysis of an already uploaded capture. Only valid for `failed` with `retryAllowed`. */
    fun retryJob(ownerUserId: String, pending: RppgPendingJob): RppgAcceptedJob {
        val response = client.execute(ApiRequest("POST", endpoints.retryRppgJob(pending.jobId), body = "{}"))
        if (response.statusCode != HTTP_ACCEPTED) {
            throw ApiHttpException(response.statusCode, response.body)
        }
        val accepted = RppgAcceptedJob.parse(response.body)
        store.savePending(
            RppgPendingJob(
                ownerUserId = ownerUserId,
                jobId = accepted.jobId,
                captureId = accepted.captureId,
                clientCaptureId = pending.clientCaptureId,
            ),
        )
        return accepted
    }

    /**
     * Polls until the job reaches a terminal status, then persists the result and clears the
     * pending ids.
     *
     * A transport error is not a terminal status — it retries on the same tick and the 3-minute
     * ceiling covers it. A 401 that survives the client's own refresh propagates as
     * [com.neurotruth.mobile.core.net.AuthenticationRequiredException].
     */
    suspend fun awaitTerminal(
        ownerUserId: String,
        jobId: String,
        startedAtMs: Long = System.currentTimeMillis(),
        onProgress: (RppgPollProgress) -> Unit = {},
    ): RppgJobSnapshot {
        while (true) {
            val elapsed = System.currentTimeMillis() - startedAtMs
            val snapshot = try {
                withContext(Dispatchers.IO) { job(jobId) }
            } catch (error: Throwable) {
                if (!shouldRetryRppgPolling(error)) throw error
                null
            }
            if (snapshot != null && snapshot.isTerminal) {
                store.saveResult(ownerUserId, snapshot)
                store.clearPending()
                return snapshot
            }
            onProgress(RppgPollProgress(elapsed, RppgPollPlan.notice(elapsed)))
            delay(RppgPollPlan.intervalMs(elapsed))
        }
    }

    /** Exactly one navigation into chat per job, across duplicate events and across restarts. */
    fun claimRoute(ownerUserId: String, jobId: String): Boolean =
        routeOnce.claim(ownerUserId, jobId)

    private fun postMultipart(
        url: String,
        token: String,
        video: File,
        fields: Map<String, String>,
    ): ApiResponse {
        val boundary = "----NeuroTruth-${UUID.randomUUID()}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = AuthenticatedApiClient.DEFAULT_CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_READ_TIMEOUT_MS
            doOutput = true
            setChunkedStreamingMode(STREAM_CHUNK_BYTES)
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
                output.writeUtf8(
                    "Content-Disposition: form-data; name=\"video\"; filename=\"capture.mp4\"\r\n",
                )
                output.writeUtf8("Content-Type: video/mp4\r\n\r\n")
                video.inputStream().use { input -> input.copyTo(output, STREAM_CHUNK_BYTES) }
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

    companion object {
        /** The only accepted status of `POST /api/rppg/jobs`. */
        const val HTTP_ACCEPTED: Int = 202

        private const val UPLOAD_READ_TIMEOUT_MS = 180_000
        private const val STREAM_CHUNK_BYTES = 64 * 1024

        fun create(context: Context, client: AuthenticatedApiClient, endpoints: ApiEndpoints) =
            RppgRepository(client, endpoints, RppgJobStore(context))
    }
}

private fun JSONObject.requiredString(key: String): String =
    optString(key).trim().takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("$key missing")

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf(String::isNotEmpty)

private fun JSONObject.optFloatOrNull(key: String): Float? =
    if (!has(key) || isNull(key)) null else optDouble(key).toFloat().takeIf(Float::isFinite)

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    if (value == null) this else put(key, value)
