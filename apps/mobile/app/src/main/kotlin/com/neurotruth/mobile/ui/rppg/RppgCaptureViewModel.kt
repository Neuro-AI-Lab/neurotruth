package com.neurotruth.mobile.ui.rppg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ConsentGates
import com.neurotruth.mobile.core.RppgCapturePhase
import com.neurotruth.mobile.core.RppgContract
import com.neurotruth.mobile.core.RppgJobPolicy
import com.neurotruth.mobile.core.RppgJobStatus
import com.neurotruth.mobile.core.RppgNextStep
import com.neurotruth.mobile.core.SessionEntryPoint
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.AuthenticationRequiredException
import com.neurotruth.mobile.data.ActiveSessionStore
import com.neurotruth.mobile.data.AlertSessionEntry
import com.neurotruth.mobile.data.ProfileRepository
import com.neurotruth.mobile.data.RppgCaptureLengthException
import com.neurotruth.mobile.data.RppgJobSnapshot
import com.neurotruth.mobile.data.RppgJobStore
import com.neurotruth.mobile.data.RppgPendingJob
import com.neurotruth.mobile.data.RppgRepository
import com.neurotruth.mobile.data.RppgUploadTooLargeException
import com.neurotruth.mobile.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** NT-04R copy. Kept in one place so the capture screen renders strings and decides nothing. */
object RppgCaptureCopy {
    const val RETENTION_NOTICE: String =
        "얼굴 영상은 서버에서 암호화되어 관리자가 삭제할 때까지 보존됩니다."

    const val FINDING_FACE: String = "얼굴을 화면 안내선 안에 맞춰 주세요."
    const val STABILIZING: String = "한 얼굴을 1초 동안 유지하면 20초 촬영이 자동으로 시작됩니다."
    const val RECORDING: String = "얼굴이 1초 이상 안내 영역을 벗어나면 측정을 취소합니다."
    const val UPLOADING: String = "촬영한 영상을 안전하게 올리고 있어요."
    const val ANALYZING: String = "영상 업로드가 완료되었습니다. 분석 결과를 기다리고 있습니다."
    const val COMPLETED: String = "측정이 완료되었어요. 대화를 시작할게요."

    const val RECAPTURE: String =
        "영상 품질이 충분하지 않아 새로 측정해야 해요. 밝은 곳에서 움직이지 말고 다시 시도해 주세요."
    const val CANCELLED_BY_FACE: String = "얼굴이 1초 이상 안내 영역을 벗어나 촬영을 취소했어요."
    const val CANCELLED_BY_BACKGROUND: String = "앱이 백그라운드로 이동해 촬영을 취소했어요."
    const val DURATION_INVALID: String = "촬영 길이가 20초에 맞지 않아 다시 측정해야 해요."
    const val TOO_LARGE: String = "영상 용량이 한도를 넘어 업로드하지 못했어요. 다시 측정해 주세요."
    const val UPLOAD_FAILED: String = "업로드하지 못했어요. 다시 측정해 주세요."
    const val ANALYSIS_FAILED: String = "분석에 실패했어요. 홈으로 돌아가 주세요."
    const val LOGIN_REQUIRED: String = "다시 로그인하면 분석 상태를 이어서 확인해요."
    const val ALREADY_ROUTED: String = "이 측정 결과는 이미 대화로 이어졌어요."

    const val PERMISSION_REQUESTING: String = "얼굴을 촬영하려면 카메라 권한이 필요해요."
    const val PERMISSION_DENIED: String =
        "카메라 권한이 없으면 얼굴로 측정할 수 없어요. 권한을 허용하거나 홈으로 돌아가 주세요."
    const val PERMISSION_BLOCKED: String =
        "카메라 권한이 차단되어 있어요. 시스템 설정에서 이 앱의 카메라 권한을 켜야 측정할 수 있어요."

    const val NOT_CONSENTED: String =
        "생체신호·AI 분석·카메라 촬영·얼굴 영상 보존 동의가 모두 필요해요."
    const val NOT_READY: String = "얼굴 측정 서비스를 지금 사용할 수 없어요."
    const val CAMERA_UNAVAILABLE: String = "전면 카메라를 시작할 수 없어요."
}

data class RppgCaptureUiState(
    val phase: RppgCapturePhase = RppgCapturePhase.IDLE,
    val guidance: String = "",
    val stabilityProgress: Float = 0f,
    val remainingSeconds: Int = RppgContract.CAPTURE_SECONDS,
    val captureProgress: Float = 0f,
    val slowNotice: String? = null,
    val blockedReason: String? = null,
    val canRecapture: Boolean = false,
    val routeToChat: Boolean = false,
) {
    /** The preview and the analyzer run only while a capture is actually in progress. */
    val cameraActive: Boolean
        get() = phase == RppgCapturePhase.FINDING_FACE ||
            phase == RppgCapturePhase.STABILIZING ||
            phase == RppgCapturePhase.RECORDING

    val isRecording: Boolean get() = phase == RppgCapturePhase.RECORDING

    val isBusy: Boolean
        get() = phase == RppgCapturePhase.UPLOADING || phase == RppgCapturePhase.ANALYZING

    val phaseTitle: String
        get() = when (phase) {
            RppgCapturePhase.IDLE -> "측정 준비"
            RppgCapturePhase.FINDING_FACE -> "얼굴 찾기"
            RppgCapturePhase.STABILIZING -> "얼굴 안정화"
            RppgCapturePhase.RECORDING -> "20초 촬영 중"
            RppgCapturePhase.UPLOADING -> "업로드 중"
            RppgCapturePhase.ANALYZING -> "분석 대기"
            RppgCapturePhase.COMPLETED -> "측정 완료"
            RppgCapturePhase.RETRY_REQUIRED -> "재촬영 필요"
            RppgCapturePhase.FAILED -> "측정 실패"
        }
}

/**
 * NT-04R · 얼굴 20초 측정.
 *
 * Nothing about the state machine or the schedule is decided here: face timing belongs to
 * [com.neurotruth.mobile.core.FaceStabilityTracker], the terminal branch to [RppgJobPolicy], the
 * poll cadence and the 3-minute ceiling to [com.neurotruth.mobile.data.RppgPollPlan], and the
 * route-once claim to [com.neurotruth.mobile.core.RppgRouteOnce] over a persisted receipt store.
 *
 * A capture that has not been accepted with a 202 is disposable — the MP4 is deleted on cancel, on
 * a length violation and on a failed upload. Once accepted, the ids are on disk and the video is
 * gone from the device.
 */
class RppgCaptureViewModel(
    private val app: NeuroTruthApp,
) : ViewModel() {

    private val store = RppgJobStore(app)
    private val repository = RppgRepository(
        client = app.apiClient,
        endpoints = app.endpoints,
        store = store,
        profile = ProfileRepository(app.apiClient, app.endpoints),
    )
    private val sessionRepository = SessionRepository(
        client = app.apiClient,
        endpoints = app.endpoints,
        activeSessionStore = ActiveSessionStore(app),
    )

    private val _state = MutableStateFlow(RppgCaptureUiState())
    val state: StateFlow<RppgCaptureUiState> = _state.asStateFlow()

    private var ownerUserId: String? = null
    private var clientCaptureId: String? = null
    private var captureFile: File? = null
    private var capturedAtMs: Long = 0L
    private var recordingStartedAtMs: Long = 0L
    private var countdownJob: Job? = null
    private var trackingJob: Job? = null

    init {
        viewModelScope.launch { start() }
    }

    /**
     * Entry conditions are re-checked here and not only on Home: consent can be withdrawn and the
     * service can go unavailable between the button press and the camera opening.
     */
    private suspend fun start() {
        val preparation = withContext(Dispatchers.IO) {
            runCatching {
                val snapshot = ProfileRepository(app.apiClient, app.endpoints).me()
                Preparation(
                    ownerUserId = snapshot.userId,
                    canCapture = ConsentGates(snapshot.consent).canCaptureRppg,
                    ready = repository.isReady(),
                    pending = repository.pendingJob(),
                )
            }
        }.getOrElse {
            block(if (it is AuthenticationRequiredException) RppgCaptureCopy.LOGIN_REQUIRED else RppgCaptureCopy.NOT_READY)
            return
        }

        ownerUserId = preparation.ownerUserId

        // A job left in flight by a process death is resumed before anything else — a new capture
        // while one is still analysing would create a second job for the same episode.
        val pending = preparation.pending
        if (pending != null && pending.ownerUserId == preparation.ownerUserId) {
            clientCaptureId = pending.clientCaptureId
            enterAnalyzing()
            trackJob(pending)
            return
        }

        if (!preparation.canCapture) return block(RppgCaptureCopy.NOT_CONSENTED)
        if (!preparation.ready) return block(RppgCaptureCopy.NOT_READY)
        beginCapture()
    }

    private data class Preparation(
        val ownerUserId: String,
        val canCapture: Boolean,
        val ready: Boolean,
        val pending: RppgPendingJob?,
    )

    /** Arms a fresh capture. The tracker is reset by the screen on every new `clientCaptureId`. */
    fun beginCapture() {
        clientCaptureId = UUID.randomUUID().toString()
        discardLocalCapture()
        _state.update {
            it.copy(
                phase = RppgCapturePhase.FINDING_FACE,
                guidance = RppgCaptureCopy.FINDING_FACE,
                stabilityProgress = 0f,
                remainingSeconds = RppgContract.CAPTURE_SECONDS,
                captureProgress = 0f,
                slowNotice = null,
                blockedReason = null,
                canRecapture = false,
            )
        }
    }

    fun onStability(stableMs: Long) {
        val current = _state.value.phase
        if (current != RppgCapturePhase.FINDING_FACE && current != RppgCapturePhase.STABILIZING) return
        val progress = (stableMs / STABLE_REQUIRED_MS.toFloat()).coerceIn(0f, 1f)
        _state.update {
            it.copy(
                phase = if (stableMs > 0L) RppgCapturePhase.STABILIZING else RppgCapturePhase.FINDING_FACE,
                guidance = if (stableMs > 0L) RppgCaptureCopy.STABILIZING else RppgCaptureCopy.FINDING_FACE,
                stabilityProgress = progress,
            )
        }
    }

    fun onRecordingStarted(file: File, startedAtMs: Long) {
        captureFile = file
        capturedAtMs = startedAtMs
        recordingStartedAtMs = startedAtMs
        _state.update {
            it.copy(
                phase = RppgCapturePhase.RECORDING,
                guidance = RppgCaptureCopy.RECORDING,
                stabilityProgress = 1f,
                remainingSeconds = RppgContract.CAPTURE_SECONDS,
                captureProgress = 0f,
            )
        }
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isActive && _state.value.phase == RppgCapturePhase.RECORDING) {
                val elapsed = System.currentTimeMillis() - recordingStartedAtMs
                val remaining = ((RppgContract.CAPTURE_MS - elapsed + 999L) / 1_000L)
                    .coerceIn(0L, RppgContract.CAPTURE_SECONDS.toLong())
                _state.update {
                    it.copy(
                        remainingSeconds = remaining.toInt(),
                        captureProgress = (elapsed / RppgContract.CAPTURE_MS.toFloat()).coerceIn(0f, 1f),
                    )
                }
                delay(COUNTDOWN_TICK_MS)
            }
        }
    }

    /** A blink never reaches this — the tracker only cancels after a full second of loss. */
    fun onCaptureCancelled(reason: String) {
        if (!_state.value.cameraActive && _state.value.phase != RppgCapturePhase.UPLOADING) return
        countdownJob?.cancel()
        discardLocalCapture()
        _state.update {
            it.copy(
                phase = RppgCapturePhase.FAILED,
                guidance = reason,
                stabilityProgress = 0f,
                captureProgress = 0f,
                canRecapture = true,
            )
        }
    }

    /**
     * The recorded length is read from the finalize event, never assumed from the 20-second timer:
     * a device that stops the encoder early would otherwise upload a capture the server rejects.
     */
    fun onRecordingFinished(durationMs: Long) {
        countdownJob?.cancel()
        val file = captureFile ?: return onCaptureCancelled(RppgCaptureCopy.UPLOAD_FAILED)
        if (!RppgContract.isValidDuration(durationMs)) {
            discardLocalCapture()
            _state.update {
                it.copy(
                    phase = RppgCapturePhase.RETRY_REQUIRED,
                    guidance = RppgCaptureCopy.DURATION_INVALID,
                    canRecapture = true,
                )
            }
            return
        }
        upload(file, durationMs)
    }

    private fun upload(file: File, durationMs: Long) {
        val owner = ownerUserId ?: return block(RppgCaptureCopy.LOGIN_REQUIRED)
        val captureId = clientCaptureId ?: return onCaptureCancelled(RppgCaptureCopy.UPLOAD_FAILED)
        _state.update {
            it.copy(
                phase = RppgCapturePhase.UPLOADING,
                guidance = RppgCaptureCopy.UPLOADING,
                canRecapture = false,
            )
        }
        viewModelScope.launch {
            val accepted = withContext(Dispatchers.IO) {
                runCatching {
                    repository.submit(
                        ownerUserId = owner,
                        video = file,
                        clientCaptureId = captureId,
                        capturedAtMs = capturedAtMs,
                        durationMs = durationMs,
                        sessionId = activeSessionId(),
                    )
                }
            }
            accepted
                .onSuccess { job ->
                    captureFile = null
                    enterAnalyzing()
                    trackJob(RppgPendingJob(owner, job.jobId, job.captureId, captureId))
                }
                .onFailure { error ->
                    discardLocalCapture()
                    _state.update {
                        it.copy(
                            phase = RppgCapturePhase.FAILED,
                            guidance = uploadFailureMessage(error),
                            canRecapture = error !is AuthenticationRequiredException,
                        )
                    }
                }
        }
    }

    private fun enterAnalyzing() {
        _state.update {
            it.copy(
                phase = RppgCapturePhase.ANALYZING,
                guidance = RppgCaptureCopy.ANALYZING,
                slowNotice = null,
                canRecapture = false,
            )
        }
    }

    /** Polls one job to a terminal status and branches with [RppgJobPolicy]. */
    private fun trackJob(pending: RppgPendingJob) {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            var current = pending
            while (isActive) {
                val snapshot = runCatching {
                    repository.awaitTerminal(current.ownerUserId, current.jobId) { progress ->
                        _state.update { it.copy(slowNotice = progress.slowNotice) }
                    }
                }.getOrElse { error ->
                    _state.update {
                        it.copy(
                            phase = RppgCapturePhase.FAILED,
                            guidance = if (error is AuthenticationRequiredException) {
                                RppgCaptureCopy.LOGIN_REQUIRED
                            } else {
                                RppgCaptureCopy.ANALYSIS_FAILED
                            },
                        )
                    }
                    return@launch
                }

                val status = snapshot.status ?: RppgJobStatus.FAILED
                when (RppgJobPolicy.next(status, snapshot.retryAllowed)) {
                    RppgNextStep.KeepPolling -> Unit
                    RppgNextStep.RouteToChat -> {
                        completeAndRoute(current.ownerUserId, snapshot)
                        return@launch
                    }
                    RppgNextStep.Recapture -> {
                        _state.update {
                            it.copy(
                                phase = RppgCapturePhase.RETRY_REQUIRED,
                                guidance = RppgCaptureCopy.RECAPTURE,
                                slowNotice = null,
                                canRecapture = true,
                            )
                        }
                        return@launch
                    }
                    RppgNextStep.RetryJob -> {
                        val retried = withContext(Dispatchers.IO) {
                            runCatching { repository.retryJob(current.ownerUserId, current) }
                        }.getOrNull()
                        if (retried == null) {
                            _state.update {
                                it.copy(
                                    phase = RppgCapturePhase.FAILED,
                                    guidance = RppgCaptureCopy.ANALYSIS_FAILED,
                                    slowNotice = null,
                                    canRecapture = true,
                                )
                            }
                            return@launch
                        }
                        current = current.copy(jobId = retried.jobId, captureId = retried.captureId)
                        enterAnalyzing()
                    }
                    is RppgNextStep.Abandon -> {
                        _state.update {
                            it.copy(
                                phase = RppgCapturePhase.FAILED,
                                guidance = RppgCaptureCopy.ANALYSIS_FAILED,
                                slowNotice = null,
                                canRecapture = false,
                            )
                        }
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * The result is already persisted by [RppgRepository.awaitTerminal]. Navigation happens only if
     * this process wins the route-once claim, so a duplicate completion event opens nothing.
     */
    private suspend fun completeAndRoute(ownerUserId: String, snapshot: RppgJobSnapshot) {
        val claimed = repository.claimRoute(ownerUserId, snapshot.jobId)
        if (!claimed) {
            _state.update {
                it.copy(
                    phase = RppgCapturePhase.COMPLETED,
                    guidance = RppgCaptureCopy.ALREADY_ROUTED,
                    slowNotice = null,
                )
            }
            return
        }
        // Let ChatViewModel create the session. Pre-creating it here makes Chat see a resumed
        // session and incorrectly skip the AUQ offered to every newly created session.
        AlertSessionEntry.arm(SessionEntryPoint.RPPG_COMPLETION)
        _state.update {
            it.copy(
                phase = RppgCapturePhase.COMPLETED,
                guidance = RppgCaptureCopy.COMPLETED,
                slowNotice = null,
                routeToChat = true,
            )
        }
    }

    private fun block(reason: String) {
        discardLocalCapture()
        _state.update {
            it.copy(
                phase = RppgCapturePhase.FAILED,
                guidance = reason,
                blockedReason = reason,
                canRecapture = false,
            )
        }
    }

    fun onCameraUnavailable() = block(RppgCaptureCopy.CAMERA_UNAVAILABLE)

    private fun activeSessionId(): String? = sessionRepository.activeSessionId()
        ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }

    private fun discardLocalCapture() {
        captureFile?.delete()
        captureFile = null
    }

    override fun onCleared() {
        countdownJob?.cancel()
        discardLocalCapture()
        super.onCleared()
    }

    private fun uploadFailureMessage(error: Throwable): String = when (error) {
        is RppgUploadTooLargeException -> RppgCaptureCopy.TOO_LARGE
        is RppgCaptureLengthException -> RppgCaptureCopy.DURATION_INVALID
        is AuthenticationRequiredException -> RppgCaptureCopy.LOGIN_REQUIRED
        is ApiHttpException -> when (error.statusCode) {
            403 -> RppgCaptureCopy.NOT_CONSENTED
            413 -> RppgCaptureCopy.TOO_LARGE
            415, 422 -> RppgCaptureCopy.DURATION_INVALID
            503 -> RppgCaptureCopy.NOT_READY
            else -> RppgCaptureCopy.UPLOAD_FAILED
        }
        else -> RppgCaptureCopy.UPLOAD_FAILED
    }

    companion object {
        private const val STABLE_REQUIRED_MS = 1_000L
        private const val COUNTDOWN_TICK_MS = 100L

        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RppgCaptureViewModel(app) as T
            }
    }
}
