package com.example.healthsensor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class RppgPendingJob(
    val ownerUserId: String,
    val jobId: String,
    val captureId: String,
    val clientCaptureId: String
)

data class RppgMonitoringSnapshot(val uploadEnabled: Boolean, val sseEnabled: Boolean)

interface RppgRouteReceiptStore {
    fun wasRouted(ownerUserId: String, jobId: String): Boolean
    fun markRouted(ownerUserId: String, jobId: String): Boolean
}

class RppgRouteOnce(private val store: RppgRouteReceiptStore) {
    @Synchronized
    fun claim(ownerUserId: String, jobId: String): Boolean {
        if (store.wasRouted(ownerUserId, jobId)) return false
        return store.markRouted(ownerUserId, jobId)
    }
}

object RppgPauseRecoveryPolicy {
    fun restore(
        snapshot: RppgMonitoringSnapshot,
        canUpload: Boolean,
        canReceivePredictions: Boolean
    ): RppgMonitoringSnapshot = RppgMonitoringSnapshot(
        uploadEnabled = snapshot.uploadEnabled && canUpload,
        sseEnabled = snapshot.sseEnabled && canReceivePredictions
    )
}

class RppgJobStore(context: Context) : RppgRouteReceiptStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savePending(value: RppgPendingJob): Boolean =
        prefs.edit()
            .putString(KEY_OWNER, value.ownerUserId)
            .putString(KEY_JOB_ID, value.jobId)
            .putString(KEY_CAPTURE_ID, value.captureId)
            .putString(KEY_CLIENT_CAPTURE_ID, value.clientCaptureId)
            .commit()

    fun loadPending(): RppgPendingJob? {
        val owner = prefs.getString(KEY_OWNER, null) ?: return null
        val job = prefs.getString(KEY_JOB_ID, null) ?: return null
        val capture = prefs.getString(KEY_CAPTURE_ID, null) ?: return null
        val clientCapture = prefs.getString(KEY_CLIENT_CAPTURE_ID, null) ?: return null
        return RppgPendingJob(owner, job, capture, clientCapture)
    }

    fun clearPending(): Boolean =
        prefs.edit()
            .remove(KEY_OWNER)
            .remove(KEY_JOB_ID)
            .remove(KEY_CAPTURE_ID)
            .remove(KEY_CLIENT_CAPTURE_ID)
            .commit()

    fun saveResult(ownerUserId: String, result: RppgJobResult): Boolean =
        prefs.edit()
            .putString(KEY_RESULT_OWNER, ownerUserId)
            .putString(KEY_RESULT, result.toJson().toString())
            .commit()

    fun loadResult(ownerUserId: String): RppgJobResult? {
        if (prefs.getString(KEY_RESULT_OWNER, null) != ownerUserId) return null
        val encoded = prefs.getString(KEY_RESULT, null) ?: return null
        return runCatching { RppgJobResult.fromJson(JSONObject(encoded)) }.getOrNull()
    }

    override fun wasRouted(ownerUserId: String, jobId: String): Boolean =
        prefs.getString(KEY_ROUTED_OWNER, null) == ownerUserId &&
            prefs.getString(KEY_ROUTED_JOB_ID, null) == jobId

    override fun markRouted(ownerUserId: String, jobId: String): Boolean =
        prefs.edit()
            .putString(KEY_ROUTED_OWNER, ownerUserId)
            .putString(KEY_ROUTED_JOB_ID, jobId)
            .commit()

    companion object {
        private const val PREFS = "rppg_jobs"
        private const val KEY_OWNER = "pending_owner"
        private const val KEY_JOB_ID = "pending_job_id"
        private const val KEY_CAPTURE_ID = "pending_capture_id"
        private const val KEY_CLIENT_CAPTURE_ID = "pending_client_capture_id"
        private const val KEY_RESULT_OWNER = "result_owner"
        private const val KEY_RESULT = "latest_result"
        private const val KEY_ROUTED_OWNER = "routed_owner"
        private const val KEY_ROUTED_JOB_ID = "routed_job_id"
    }
}

/**
 * Keeps watch reception untouched while suspending only phone upload and SSE application.
 * The original toggles are durable so a process restart can restore them exactly.
 */
class RppgMonitoringPauseLatch(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun acquire() {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putBoolean(KEY_UPLOAD, PhoneMonitoringState.isUploadEnabled.value)
                .putBoolean(KEY_SSE, PhoneMonitoringState.isPredictionReceiverEnabled.value)
                .commit()
        }
        PhoneMonitoringState.isCameraPauseActive.value = true
    }

    fun recover(): Boolean {
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        PhoneMonitoringState.isCameraPauseActive.value = active
        return active
    }

    fun release(restoreMonitoring: Boolean = true) {
        val wasActive = prefs.getBoolean(KEY_ACTIVE, false)
        val upload = prefs.getBoolean(KEY_UPLOAD, false)
        val sse = prefs.getBoolean(KEY_SSE, false)
        prefs.edit().clear().commit()
        PhoneMonitoringState.isCameraPauseActive.value = false
        if (wasActive && restoreMonitoring) {
            val restored = RppgPauseRecoveryPolicy.restore(
                RppgMonitoringSnapshot(upload, sse),
                canUpload = MobileAuthRuntime.canUpload(),
                canReceivePredictions = MobileAuthRuntime.canReceivePredictions()
            )
            PhoneMonitoringState.isUploadEnabled.value = restored.uploadEnabled
            PhoneMonitoringState.isPredictionReceiverEnabled.value = restored.sseEnabled
        }
    }

    fun snapshot(): RppgMonitoringSnapshot? = if (prefs.getBoolean(KEY_ACTIVE, false)) {
        RppgMonitoringSnapshot(
            prefs.getBoolean(KEY_UPLOAD, false),
            prefs.getBoolean(KEY_SSE, false)
        )
    } else null

    companion object {
        private const val PREFS = "rppg_pause_latch"
        private const val KEY_ACTIVE = "active"
        private const val KEY_UPLOAD = "upload_was_enabled"
        private const val KEY_SSE = "sse_was_enabled"
    }
}

object RppgPhoneState {
    val latestResult = MutableStateFlow<RppgJobResult?>(null)
}
