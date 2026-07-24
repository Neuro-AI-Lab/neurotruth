package com.neurotruth.mobile.core

object RppgContract {
    const val CAPTURE_SECONDS: Int = 20
    const val CAPTURE_MS: Long = CAPTURE_SECONDS * 1_000L
    const val MIN_DURATION_MS: Long = 19_500L
    const val MAX_DURATION_MS: Long = 20_500L

    /** Server default. Do not hardcode a smaller number in user-facing copy. */
    const val MAX_UPLOAD_BYTES: Long = 40L * 1024L * 1024L

    const val POLL_INTERVAL_MS: Long = 2_000L

    /** After this, stop the foreground spinner and fall back to a slow background check. */
    const val POLL_CEILING_MS: Long = 3 * 60_000L
    const val BACKGROUND_POLL_INTERVAL_MS: Long = 30_000L

    const val SLOW_ANALYSIS_NOTICE: String =
        "분석이 예상보다 오래 걸리고 있어요 — 완료되면 알려드릴게요"

    fun isValidDuration(durationMs: Long): Boolean = durationMs in MIN_DURATION_MS..MAX_DURATION_MS
}

enum class RppgJobStatus(val wireValue: String) {
    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    RETRY_REQUIRED("retry_required"),
    FAILED("failed"),
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == RETRY_REQUIRED || this == FAILED

    companion object {
        fun fromWire(value: String?): RppgJobStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}

sealed class RppgNextStep {
    object KeepPolling : RppgNextStep()

    /** Route once into the session flow, then navigate to chat. */
    object RouteToChat : RppgNextStep()

    /** Quality failure needs a fresh capture, not a retry of the same job. */
    object Recapture : RppgNextStep()

    /** Transient failure; the retry endpoint may create one new attempt. */
    object RetryJob : RppgNextStep()

    data class Abandon(val reason: String?) : RppgNextStep()
}

object RppgJobPolicy {
    fun next(status: RppgJobStatus, retryAllowed: Boolean): RppgNextStep = when (status) {
        RppgJobStatus.QUEUED, RppgJobStatus.RUNNING -> RppgNextStep.KeepPolling
        RppgJobStatus.COMPLETED -> RppgNextStep.RouteToChat
        RppgJobStatus.RETRY_REQUIRED -> RppgNextStep.Recapture
        RppgJobStatus.FAILED ->
            if (retryAllowed) RppgNextStep.RetryJob else RppgNextStep.Abandon(null)
    }
}

enum class RppgCapturePhase {
    IDLE,
    FINDING_FACE,
    STABILIZING,
    RECORDING,
    UPLOADING,
    ANALYZING,
    COMPLETED,
    RETRY_REQUIRED,
    FAILED,
}

sealed class FaceStabilityEvent {
    data class Progress(val stableMs: Long) : FaceStabilityEvent()
    object StartRecording : FaceStabilityEvent()
    object KeepRecording : FaceStabilityEvent()
    object CancelRecording : FaceStabilityEvent()
}

/**
 * Drives automatic capture from face detection frames.
 *
 * Exactly one face must sit inside the guide region for [stableRequiredMs] before recording starts.
 * Once recording, a momentary loss is tolerated: capture cancels only after [lossCancelMs] of
 * continuous invalidity, so a blink does not throw away a 20-second take.
 */
class FaceStabilityTracker(
    private val stableRequiredMs: Long = 1_000L,
    private val lossCancelMs: Long = 1_000L,
) {
    private var candidateTrackingId: Int? = null
    private var stableSinceMs: Long? = null
    private var recording: Boolean = false
    private var invalidSinceMs: Long? = null

    fun update(
        nowMs: Long,
        faceCount: Int,
        trackingId: Int?,
        insideGuide: Boolean,
    ): FaceStabilityEvent {
        val valid = faceCount == 1 && insideGuide

        if (!recording) {
            if (!valid) {
                candidateTrackingId = null
                stableSinceMs = null
                return FaceStabilityEvent.Progress(0L)
            }
            if (trackingId != candidateTrackingId || stableSinceMs == null) {
                candidateTrackingId = trackingId
                stableSinceMs = nowMs
                return FaceStabilityEvent.Progress(0L)
            }
            val stableMs = nowMs - (stableSinceMs ?: nowMs)
            return if (stableMs >= stableRequiredMs) {
                recording = true
                invalidSinceMs = null
                FaceStabilityEvent.StartRecording
            } else {
                FaceStabilityEvent.Progress(stableMs)
            }
        }

        if (valid && trackingId == candidateTrackingId) {
            invalidSinceMs = null
            return FaceStabilityEvent.KeepRecording
        }
        val lostAt = invalidSinceMs
        if (lostAt == null) {
            invalidSinceMs = nowMs
            return FaceStabilityEvent.KeepRecording
        }
        return if (nowMs - lostAt >= lossCancelMs) {
            reset()
            FaceStabilityEvent.CancelRecording
        } else {
            FaceStabilityEvent.KeepRecording
        }
    }

    fun reset() {
        candidateTrackingId = null
        stableSinceMs = null
        recording = false
        invalidSinceMs = null
    }
}

/**
 * Guarantees a completed job creates a session and navigates exactly once, however many times the
 * completion event is observed.
 */
interface RppgRouteReceiptStore {
    fun wasRouted(ownerUserId: String, jobId: String): Boolean
    fun markRouted(ownerUserId: String, jobId: String): Boolean
}

class RppgRouteOnce(private val store: RppgRouteReceiptStore) {
    @Synchronized
    fun claim(ownerUserId: String, jobId: String): Boolean {
        if (ownerUserId.isBlank() || jobId.isBlank()) return false
        if (store.wasRouted(ownerUserId, jobId)) return false
        return store.markRouted(ownerUserId, jobId)
    }
}
