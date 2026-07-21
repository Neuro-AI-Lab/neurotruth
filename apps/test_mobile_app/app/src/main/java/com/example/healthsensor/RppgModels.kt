package com.example.healthsensor

import org.json.JSONObject

enum class RppgCapturePhase {
    IDLE,
    FINDING_FACE,
    STABILIZING,
    RECORDING,
    UPLOADING,
    ANALYZING,
    COMPLETED,
    RETRY_REQUIRED,
    FAILED
}

data class RppgServiceStatus(
    val enabled: Boolean,
    val available: Boolean,
    val modelLoaded: Boolean,
    val checkpoint: String? = null
) {
    val canStart: Boolean get() = enabled && available && modelLoaded

    companion object {
        fun parse(body: String): RppgServiceStatus {
            val root = JSONObject(body)
            val ready = root.optBoolean(
                "ready",
                root.optBoolean("available", false) && root.optBoolean("modelLoaded", false)
            )
            return RppgServiceStatus(
                enabled = root.optBoolean("enabled", false),
                available = ready,
                modelLoaded = ready,
                checkpoint = root.optNullableString("checkpoint")
            )
        }
    }
}

data class RppgAcceptedJob(
    val jobId: String,
    val captureId: String,
    val status: String
) {
    companion object {
        fun parse(body: String): RppgAcceptedJob {
            val root = JSONObject(body)
            return RppgAcceptedJob(
                jobId = root.requiredString("jobId"),
                captureId = root.requiredString("captureId"),
                status = root.requiredString("status")
            )
        }
    }
}

data class RppgJobResult(
    val jobId: String,
    val captureId: String,
    val status: String,
    val capturedAtMs: Long,
    val classIndex: Int? = null,
    val confidence: Float? = null,
    val heartRateBpm: Float? = null,
    val qualityScore: Float? = null,
    val modelName: String? = null,
    val checkpoint: String? = null,
    val processingMs: Long? = null,
    val rppgSampleCount: Int? = null,
    val rppgSamplingHz: Float? = null,
    val alertAction: String? = null,
    val failureCode: String? = null,
    val retryAllowed: Boolean = false
) {
    val terminal: Boolean get() = status in setOf("completed", "retry_required", "failed")

    fun toPrediction(): CravingPrediction? = classIndex?.takeIf { it in 0..1 }?.let { value ->
        val classOneProbability = confidence?.let { if (value == 1) it else 1f - it }
        CravingPrediction(
            cravingClass = value,
            timestampMs = capturedAtMs,
            rawBody = JSONObject()
                .put("source", "camera_rppg")
                .put("jobId", jobId)
                .put("class", value)
                .put("confidence", confidence)
                .toString(),
            confidence = confidence,
            cravingProbability = classOneProbability,
            classProbabilities = classOneProbability?.let { probability ->
                mapOf("low" to 1f - probability, "high" to probability)
            }.orEmpty(),
            source = "camera_rppg",
            alert = AlertMetadata(
                alertAction = alertAction,
                isPresent = alertAction != null
            )
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("jobId", jobId)
        .put("captureId", captureId)
        .put("status", status)
        .put("capturedAtMs", capturedAtMs)
        .putNullable("classIndex", classIndex)
        .putNullable("confidence", confidence)
        .putNullable("heartRateBpm", heartRateBpm)
        .putNullable("qualityScore", qualityScore)
        .putNullable("modelName", modelName)
        .putNullable("checkpoint", checkpoint)
        .putNullable("processingMs", processingMs)
        .putNullable("rppgSampleCount", rppgSampleCount)
        .putNullable("rppgSamplingHz", rppgSamplingHz)
        .putNullable("alertAction", alertAction)
        .putNullable("failureCode", failureCode)
        .put("retryAllowed", retryAllowed)

    companion object {
        fun parse(body: String): RppgJobResult = fromJson(JSONObject(body))

        fun fromJson(root: JSONObject): RppgJobResult = RppgJobResult(
            jobId = root.requiredString("jobId"),
            captureId = root.requiredString("captureId"),
            status = root.requiredString("status"),
            capturedAtMs = root.optLong("capturedAtMs", 0L),
            classIndex = root.optIntOrNull("classIndex"),
            confidence = root.optFloatOrNull("confidence"),
            heartRateBpm = root.optFloatOrNull("heartRateBpm"),
            qualityScore = root.optFloatOrNull("qualityScore"),
            modelName = root.optNullableString("modelName"),
            checkpoint = root.optNullableString("checkpoint"),
            processingMs = root.optLongOrNull("processingMs"),
            rppgSampleCount = root.optIntOrNull("rppgSampleCount"),
            rppgSamplingHz = root.optFloatOrNull("rppgSamplingHz"),
            alertAction = root.optNullableString("alertAction"),
            failureCode = root.optNullableString("failureCode"),
            retryAllowed = root.optBoolean("retryAllowed", false)
        )
    }
}

sealed class FaceStabilityEvent {
    data class Progress(val stableMs: Long) : FaceStabilityEvent()
    object StartRecording : FaceStabilityEvent()
    object KeepRecording : FaceStabilityEvent()
    object CancelRecording : FaceStabilityEvent()
}

/** Pure state machine so face timing behavior can be tested without CameraX. */
class FaceStabilityTracker(
    private val stableRequiredMs: Long = 1_000L,
    private val lossCancelMs: Long = 1_000L
) {
    private var candidateId: Int? = null
    private var stableSinceMs: Long? = null
    private var invalidSinceMs: Long? = null
    private var recording = false

    fun update(nowMs: Long, faceCount: Int, trackingId: Int?, insideGuide: Boolean): FaceStabilityEvent {
        val valid = faceCount == 1 && insideGuide
        if (!recording) {
            if (!valid) {
                resetCandidate()
                return FaceStabilityEvent.Progress(0L)
            }
            if (candidateId != trackingId || stableSinceMs == null) {
                candidateId = trackingId
                stableSinceMs = nowMs
            }
            val stableMs = (nowMs - (stableSinceMs ?: nowMs)).coerceAtLeast(0L)
            if (stableMs >= stableRequiredMs) {
                recording = true
                invalidSinceMs = null
                return FaceStabilityEvent.StartRecording
            }
            return FaceStabilityEvent.Progress(stableMs)
        }

        if (valid && (candidateId == null || candidateId == trackingId)) {
            invalidSinceMs = null
            return FaceStabilityEvent.KeepRecording
        }
        val lostAt = invalidSinceMs ?: nowMs.also { invalidSinceMs = it }
        return if (nowMs - lostAt >= lossCancelMs) {
            FaceStabilityEvent.CancelRecording
        } else {
            FaceStabilityEvent.KeepRecording
        }
    }

    fun reset() {
        recording = false
        invalidSinceMs = null
        resetCandidate()
    }

    private fun resetCandidate() {
        candidateId = null
        stableSinceMs = null
    }
}

object RppgPredictionRoutingPolicy {
    /** Camera predictions are a phone-only source even when notification consent is active. */
    const val relayToWatch: Boolean = false
    fun allowPhonePresentation(canNotify: Boolean): Boolean = canNotify
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

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    if (value == null) this else put(key, value)
