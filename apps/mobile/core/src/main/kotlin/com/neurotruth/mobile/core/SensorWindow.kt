package com.neurotruth.mobile.core

/** The upload contract for `POST /api/sensor-windows`. */
object SensorWindowContract {
    const val WINDOW_MS: Long = 20_000L
    const val MIN_WINDOW_MS: Long = 19_500L
    const val MAX_WINDOW_MS: Long = 20_500L
    const val UPLOAD_INTERVAL_MS: Long = 10_000L
    const val WARM_UP_MS: Long = 20_000L

    const val PPG_HZ: Int = 25
    const val PPG_SAMPLE_INTERVAL_MS: Long = 40L
    const val PPG_SAMPLES_PER_CHANNEL: Int = 500
    const val EDA_HZ: Int = 1
    const val EDA_SAMPLE_INTERVAL_MS: Long = 1_000L
    const val EDA_SAMPLES: Int = 20

    const val SYNC_MODE: String = "fixed_grid_ppg_25hz_eda_1hz_continuous"
    const val SYNC_FILL_MODE: String = "linear_interpolation_nearest_edge_hold"

    fun isValidWindow(windowStartMs: Long, windowEndMs: Long): Boolean {
        val span = windowEndMs - windowStartMs
        return span > 0 && span in MIN_WINDOW_MS..MAX_WINDOW_MS
    }
}

enum class UploadFailureAction {
    /** Refresh failed or the credential is gone; stop uploading and sign out. */
    AUTHENTICATION_REQUIRED,

    /** The server already holds this window with different content; drop only this one. */
    DROP_AND_CONTINUE,

    /** Transient; re-send the byte-identical payload under the same clientWindowId. */
    RETRY_SAME_PAYLOAD,

    /** Consent or validation rejected it; pause uploading rather than hammering the server. */
    PAUSE_AND_DROP,
}

object UploadFailurePolicy {
    fun resolve(statusCode: Int): UploadFailureAction = when {
        statusCode == 401 -> UploadFailureAction.AUTHENTICATION_REQUIRED
        statusCode == 409 -> UploadFailureAction.DROP_AND_CONTINUE
        statusCode == 408 || statusCode == 429 -> UploadFailureAction.RETRY_SAME_PAYLOAD
        statusCode in 400..499 -> UploadFailureAction.PAUSE_AND_DROP
        else -> UploadFailureAction.RETRY_SAME_PAYLOAD
    }
}

/**
 * Bounded retry buffer for windows that could not be uploaded.
 *
 * Each window carries 1,500 PPG samples plus 20 EDA samples, so an unbounded queue exhausts memory
 * and disk in exactly the poor-connectivity conditions it exists for. Oldest entries are evicted
 * first and the caller surfaces [DROPPED_NOTICE] when that happens.
 */
class OfflineWindowQueue<T>(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val buffer = ArrayDeque<T>()
    private var droppedCount: Long = 0L

    @Synchronized
    fun offer(window: T): Boolean {
        var dropped = false
        while (buffer.size >= capacity) {
            buffer.removeFirst()
            droppedCount += 1
            dropped = true
        }
        buffer.addLast(window)
        return !dropped
    }

    @Synchronized
    fun poll(): T? = buffer.removeFirstOrNull()

    @Synchronized
    fun peek(): T? = buffer.firstOrNull()

    @Synchronized
    fun size(): Int = buffer.size

    @Synchronized
    fun dropped(): Long = droppedCount

    @Synchronized
    fun clear() {
        buffer.clear()
        droppedCount = 0L
    }

    companion object {
        /** Roughly ten minutes at the ten-second cadence. Provisional for P0. */
        const val DEFAULT_CAPACITY: Int = 60
        const val DROPPED_NOTICE: String = "일부 구간이 저장되지 않았습니다"
    }
}

/**
 * Keys the stable `clientWindowId` for a window.
 *
 * A retry must reuse the same id with byte-identical content, and the id has to survive process
 * death — so it is derived from `(session, sequence)` and persisted, never minted at send time.
 */
object ClientWindowKey {
    fun of(sessionId: String, sequence: Long): String {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(sequence >= 0) { "sequence must not be negative" }
        return "window:$sessionId:$sequence"
    }
}
