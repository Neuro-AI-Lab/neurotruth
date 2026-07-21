package com.example.healthsensor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class RppgViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway: RppgGateway = RppgApiClient(MobileApiProvider.get(application))
    private val store = RppgJobStore(application)
    private val routeOnce = RppgRouteOnce(store)
    private val pauseLatch = RppgMonitoringPauseLatch(application)

    private val _phase = MutableStateFlow(RppgCapturePhase.IDLE)
    val phase: StateFlow<RppgCapturePhase> = _phase
    private val _message = MutableStateFlow("카메라 측정을 준비 중입니다")
    val message: StateFlow<String> = _message
    private val _stabilityProgress = MutableStateFlow(0f)
    val stabilityProgress: StateFlow<Float> = _stabilityProgress
    private val _serviceStatus = MutableStateFlow<RppgServiceStatus?>(null)
    val serviceStatus: StateFlow<RppgServiceStatus?> = _serviceStatus
    private val _latestResult = RppgPhoneState.latestResult
    val latestResult: StateFlow<RppgJobResult?> = _latestResult
    private val _clientCaptureId = MutableStateFlow<String?>(null)
    val clientCaptureId: StateFlow<String?> = _clientCaptureId
    private val _hasLocalVideo = MutableStateFlow(false)
    val hasLocalVideo: StateFlow<Boolean> = _hasLocalVideo

    private var currentVideo: File? = null
    private var currentCapturedAtMs = 0L
    private var currentDurationMs = 0L
    private var uploadJob: Job? = null
    private var pollingJob: Job? = null
    private var activeOwner: String? = null

    init {
        viewModelScope.launch {
            MobileAuthRuntime.state.collect { auth ->
                val owner = auth.user?.id
                if (owner == null) {
                    pollingJob?.cancel()
                    pollingJob = null
                    activeOwner = null
                    cleanupUnacceptedVideo()
                    pauseLatch.release(restoreMonitoring = false)
                    _phase.value = RppgCapturePhase.IDLE
                } else if (activeOwner != owner) {
                    activeOwner = owner
                    _latestResult.value = store.loadResult(owner)
                    refreshStatus()
                    recoverPending(owner)
                } else if (!auth.canCaptureRppg && _phase.value in PRE_ACCEPTED_PHASES) {
                    cancelBeforeAccepted("필수 동의가 철회되어 촬영을 취소했습니다")
                }
            }
        }
    }

    fun refreshStatus() {
        if (!MobileAuthRuntime.state.value.authenticated) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { gateway.getStatus() } }
                .onSuccess {
                    _serviceStatus.value = it
                    if (_phase.value == RppgCapturePhase.IDLE) {
                        _message.value = when {
                            !it.enabled -> "카메라 rPPG 기능이 서버에서 비활성화되어 있습니다"
                            !it.available || !it.modelLoaded -> "rPPG 분석 서버를 사용할 수 없습니다"
                            else -> "카메라로 20초 측정할 수 있습니다"
                        }
                    }
                }
                .onFailure {
                    _serviceStatus.value = RppgServiceStatus(false, false, false)
                    _message.value = "rPPG 상태를 확인할 수 없습니다"
                }
        }
    }

    fun prepareCapture(): Boolean {
        val auth = MobileAuthRuntime.state.value
        if (!auth.canCaptureRppg) {
            _message.value = "생체신호·AI 분석·카메라 rPPG·얼굴 영상 보존 동의가 모두 필요합니다"
            return false
        }
        if (_serviceStatus.value?.canStart != true) {
            _message.value = "rPPG 분석 서버를 사용할 수 없습니다"
            refreshStatus()
            return false
        }
        pollingJob?.cancel()
        cleanupUnacceptedVideo()
        pauseLatch.acquire()
        _clientCaptureId.value = UUID.randomUUID().toString()
        _stabilityProgress.value = 0f
        _phase.value = RppgCapturePhase.FINDING_FACE
        _message.value = "얼굴을 화면 중앙에 맞춰 주세요"
        return true
    }

    fun updateStability(stableMs: Long) {
        if (_phase.value !in setOf(RppgCapturePhase.FINDING_FACE, RppgCapturePhase.STABILIZING)) return
        _stabilityProgress.value = (stableMs / 1_000f).coerceIn(0f, 1f)
        _phase.value = if (stableMs > 0L) RppgCapturePhase.STABILIZING else RppgCapturePhase.FINDING_FACE
        _message.value = if (stableMs > 0L) "얼굴을 그대로 유지해 주세요" else "얼굴을 화면 중앙에 맞춰 주세요"
    }

    fun recordingStarted(video: File, capturedAtMs: Long) {
        currentVideo = video
        _hasLocalVideo.value = true
        currentCapturedAtMs = capturedAtMs
        _phase.value = RppgCapturePhase.RECORDING
        _message.value = "20초 동안 움직이지 말아 주세요"
    }

    fun recordingCancelled(reason: String = "얼굴이 안내 영역을 벗어나 촬영을 취소했습니다") {
        cancelBeforeAccepted(reason)
    }

    fun recordingFinished(durationMs: Long) {
        val video = currentVideo ?: return cancelBeforeAccepted("촬영 파일을 찾을 수 없습니다")
        currentDurationMs = durationMs
        _phase.value = RppgCapturePhase.UPLOADING
        _message.value = "영상을 업로드하고 있습니다"
        upload(video)
    }

    fun retryUpload() {
        val video = currentVideo
        if (_phase.value != RppgCapturePhase.FAILED || video == null) return
        _phase.value = RppgCapturePhase.UPLOADING
        _message.value = "영상을 다시 업로드하고 있습니다"
        upload(video)
    }

    fun retryAnalysis() {
        val result = _latestResult.value ?: return
        val owner = MobileAuthRuntime.state.value.user?.id ?: return
        if (result.status != "failed" || !result.retryAllowed || !MobileAuthRuntime.state.value.canCaptureRppg) return
        pauseLatch.acquire()
        _phase.value = RppgCapturePhase.ANALYZING
        _message.value = "보관된 영상으로 분석을 다시 요청하고 있습니다"
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { gateway.retryJob(result.jobId) } }
                .onSuccess { accepted ->
                    val saved = store.savePending(
                        RppgPendingJob(owner, accepted.jobId, accepted.captureId, _clientCaptureId.value.orEmpty())
                    )
                    if (!saved) {
                        pauseLatch.release()
                        _phase.value = RppgCapturePhase.FAILED
                        _message.value = "재분석 상태를 기기에 저장할 수 없습니다"
                        return@onSuccess
                    }
                    poll(accepted.jobId, owner)
                }
                .onFailure { error ->
                    pauseLatch.release()
                    _phase.value = RppgCapturePhase.FAILED
                    _message.value = failureMessage(error, "재분석 요청에 실패했습니다")
                }
        }
    }

    fun cancelBeforeAccepted(reason: String = "카메라 측정을 취소했습니다") {
        if (_phase.value !in PRE_ACCEPTED_PHASES && _phase.value != RppgCapturePhase.FAILED) return
        cleanupUnacceptedVideo()
        pauseLatch.release()
        _phase.value = RppgCapturePhase.IDLE
        _stabilityProgress.value = 0f
        _clientCaptureId.value = null
        _message.value = reason
    }

    fun markResultRouted(jobId: String): Boolean {
        val owner = MobileAuthRuntime.state.value.user?.id ?: return false
        return routeOnce.claim(owner, jobId)
    }

    private fun upload(video: File) {
        val captureId = _clientCaptureId.value ?: return cancelBeforeAccepted("측정 식별자가 없습니다")
        val owner = MobileAuthRuntime.state.value.user?.id ?: return cancelBeforeAccepted("로그인이 필요합니다")
        val session = PhoneMonitoringState.latestServerSessionId.value
            ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    gateway.createJob(
                        video = video,
                        clientCaptureId = captureId,
                        capturedAtMs = currentCapturedAtMs,
                        durationMs = currentDurationMs,
                        sessionId = session
                    )
                }
            }.onSuccess { accepted ->
                if (!store.savePending(RppgPendingJob(owner, accepted.jobId, accepted.captureId, captureId))) {
                    _phase.value = RppgCapturePhase.FAILED
                    _message.value = "분석 상태를 기기에 저장할 수 없습니다. 업로드를 다시 시도해 주세요"
                    return@onSuccess
                }
                video.delete()
                currentVideo = null
                _hasLocalVideo.value = false
                _phase.value = RppgCapturePhase.ANALYZING
                _message.value = "rPPG와 갈망 상태를 분석하고 있습니다"
                poll(accepted.jobId, owner)
            }.onFailure { error ->
                // Before 202 the file remains only while this screen is alive for manual retry.
                _phase.value = RppgCapturePhase.FAILED
                _message.value = failureMessage(error, "업로드에 실패했습니다. 다시 시도할 수 있습니다")
            }
        }
    }

    private fun recoverPending(owner: String) {
        val pending = store.loadPending()
        if (pending == null || pending.ownerUserId != owner) {
            if (pauseLatch.recover()) pauseLatch.release()
            return
        }
        if (!pauseLatch.recover()) pauseLatch.acquire()
        _clientCaptureId.value = pending.clientCaptureId
        _phase.value = RppgCapturePhase.ANALYZING
        _message.value = "이전 rPPG 분석 상태를 복구했습니다"
        poll(pending.jobId, owner)
    }

    private fun poll(jobId: String, owner: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                val response = runCatching { withContext(Dispatchers.IO) { gateway.getJob(jobId) } }
                val error = response.exceptionOrNull()
                if (error != null) {
                    if (error is AuthenticationRequiredException ||
                        (error is ApiHttpException && error.statusCode == 401)
                    ) {
                        pauseLatch.release(restoreMonitoring = false)
                        _message.value = "다시 로그인하면 분석 상태를 이어서 확인합니다"
                        return@launch
                    }
                    _message.value = "분석 상태 확인을 재시도하고 있습니다"
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                val result = response.getOrThrow()
                if (!result.terminal) {
                    _phase.value = RppgCapturePhase.ANALYZING
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                if (!store.saveResult(owner, result)) {
                    _message.value = "결과를 기기에 저장하지 못해 다시 확인하고 있습니다"
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                store.clearPending()
                _latestResult.value = result
                pauseLatch.release()
                _phase.value = when (result.status) {
                    "completed" -> RppgCapturePhase.COMPLETED
                    "retry_required" -> RppgCapturePhase.RETRY_REQUIRED
                    else -> RppgCapturePhase.FAILED
                }
                _message.value = when (result.status) {
                    "completed" -> "카메라 rPPG 분석이 완료되었습니다"
                    "retry_required" -> "얼굴을 중앙에 두고 밝은 곳에서 움직이지 않은 상태로 다시 측정해 주세요."
                    else -> if (result.retryAllowed) {
                        "분석에 실패했습니다. 보관된 영상으로 다시 시도할 수 있습니다"
                    } else {
                        "분석에 실패했습니다. 새로 측정해 주세요"
                    }
                }
                return@launch
            }
        }
    }

    private fun cleanupUnacceptedVideo() {
        uploadJob?.cancel()
        uploadJob = null
        currentVideo?.delete()
        currentVideo = null
        _hasLocalVideo.value = false
        currentDurationMs = 0L
    }

    private fun failureMessage(error: Throwable, fallback: String): String = when (error) {
        is ApiHttpException -> when (error.statusCode) {
            403 -> "필수 동의를 확인해 주세요"
            413 -> "영상 크기가 20MiB를 초과했습니다"
            415, 422 -> "촬영 영상 형식이나 길이를 확인해 주세요"
            503 -> "rPPG 분석 서버를 사용할 수 없습니다"
            else -> "$fallback (HTTP ${error.statusCode})"
        }
        else -> fallback
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private val PRE_ACCEPTED_PHASES = setOf(
            RppgCapturePhase.FINDING_FACE,
            RppgCapturePhase.STABILIZING,
            RppgCapturePhase.RECORDING,
            RppgCapturePhase.UPLOADING
        )
    }
}
