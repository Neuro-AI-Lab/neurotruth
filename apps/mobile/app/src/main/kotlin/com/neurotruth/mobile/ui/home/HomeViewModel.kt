package com.neurotruth.mobile.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.AlertActionPolicy
import com.neurotruth.mobile.core.CameraActionPolicy
import com.neurotruth.mobile.core.ConsentGates
import com.neurotruth.mobile.core.CravingPrediction
import com.neurotruth.mobile.core.CravingStage
import com.neurotruth.mobile.core.LatestPredictionPolicy
import com.neurotruth.mobile.core.MeasurementOriginLabel
import com.neurotruth.mobile.core.SOURCE_WATCH
import com.neurotruth.mobile.core.WatchConnectionState
import com.neurotruth.mobile.core.WatchConnectionTracker
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.neurotruth.mobile.data.ProfileRepository
import com.neurotruth.mobile.data.RppgJobStore
import com.neurotruth.mobile.data.RppgRepository
import com.neurotruth.mobile.service.MonitoringBlocker
import com.neurotruth.mobile.service.MeasurementControlSnapshot
import com.neurotruth.mobile.service.MeasurementControlState
import com.neurotruth.mobile.service.MeasurementControlStatus
import com.neurotruth.mobile.service.MonitoringService
import com.neurotruth.mobile.service.MonitoringState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HomeUiState(
    // Region 1 — profile summary
    val displayName: String = "",
    val accountSummary: String = "",

    // Region 2 — craving state
    val stage: CravingStage? = null,
    val stageMessage: String = "",
    val measurementOrigin: String? = null,
    val recommendsConversation: Boolean = false,
    val cravingHiddenReason: String? = null,

    // Region 3 — watch state and camera measurement
    val watchState: WatchConnectionState = WatchConnectionState.CHECKING,
    val cameraEnabled: Boolean = false,
    val measurementControl: MeasurementControlSnapshot = MeasurementControlSnapshot(),
    val monitoringRunning: Boolean = false,

    /** The blocking reason, or the note that the capture screen will ask for the permission. */
    val cameraNotice: String? = CameraActionPolicy.REASON_WATCH_UNKNOWN,

    /** Why measurement is paused, published by the services layer. Never a craving value. */
    val monitoringNotice: String? = null,
    val droppedNotice: String? = null,

    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val developerEntryUnlocked: Boolean = false,
) {
    val hasMeasurement: Boolean get() = stage != null

    /** Never 0% and never "낮음" — an absent measurement is its own state. */
    val stageLabel: String get() = stage?.label ?: CravingStage.NO_DATA_LABEL

    val watchStateLabel: String
        get() = when (watchState) {
            WatchConnectionState.CONNECTED -> "연결됨"
            WatchConnectionState.DISCONNECTED -> "연결 안 됨"
            WatchConnectionState.CHECKING -> "확인 중"
            WatchConnectionState.ERROR -> "상태를 확인할 수 없어요"
        }

    val measurementButtonLabel: String
        get() = when (measurementControl.status) {
            MeasurementControlStatus.STARTED -> "측정 중지"
            MeasurementControlStatus.REQUESTING_START -> "시작 요청 중"
            MeasurementControlStatus.REQUESTING_STOP -> "중지 요청 중"
            MeasurementControlStatus.CONFIRMATION_REQUIRED -> "Watch에서 확인"
            MeasurementControlStatus.ERROR,
            MeasurementControlStatus.STOPPED,
            -> "측정 시작"
        }

    val measurementButtonEnabled: Boolean
        get() = watchState == WatchConnectionState.CONNECTED &&
            measurementControl.status !in setOf(
                MeasurementControlStatus.REQUESTING_START,
                MeasurementControlStatus.REQUESTING_STOP,
                MeasurementControlStatus.CONFIRMATION_REQUIRED,
            )
}

/**
 * Home copy for a paused measurement.
 *
 * A revoked notification permission means the foreground service cannot show its notification and
 * therefore cannot run at all, so it is stated plainly instead of failing silently. None of these
 * strings carries a craving value.
 */
internal fun MonitoringBlocker.notice(): String? = when (this) {
    MonitoringBlocker.NONE -> null
    MonitoringBlocker.NOTIFICATION_PERMISSION_REVOKED ->
        "알림 권한이 꺼져 있어 측정이 멈췄어요. 설정에서 알림을 허용하면 다시 시작해요."
    MonitoringBlocker.WATCH_DISCONNECTED -> "Watch 연결이 끊겨 측정이 멈췄어요."
    MonitoringBlocker.CONSENT_WITHDRAWN -> "동의가 철회되어 측정이 멈췄어요."
    MonitoringBlocker.UPLOAD_PAUSED -> "네트워크 문제로 전송이 잠시 멈췄어요."
    MonitoringBlocker.AUTHENTICATION_REQUIRED -> "다시 로그인하면 측정을 이어갑니다."
    MonitoringBlocker.SERVICE_START_FAILED -> "측정을 시작하지 못했어요. 기기 권한을 확인해 주세요."
}

internal enum class MonitoringCommand {
    START,
    STOP,
    NONE,
}

/**
 * Maps the confirmed Watch/consent state to the foreground-service action.
 *
 * CHECKING and ERROR deliberately preserve the current service state. A transient Data Layer query
 * must not tear down an otherwise healthy measurement session.
 */
internal fun monitoringCommand(
    watchState: WatchConnectionState,
    canUploadBiosignal: Boolean,
    watchStatus: MeasurementControlStatus,
    isRunning: Boolean,
): MonitoringCommand = when {
    !canUploadBiosignal && isRunning -> MonitoringCommand.STOP
    !canUploadBiosignal -> MonitoringCommand.NONE
    watchState == WatchConnectionState.DISCONNECTED && isRunning -> MonitoringCommand.STOP
    watchState == WatchConnectionState.CONNECTED &&
        watchStatus == MeasurementControlStatus.STARTED &&
        !isRunning -> MonitoringCommand.START
    watchStatus in setOf(MeasurementControlStatus.STOPPED, MeasurementControlStatus.ERROR) &&
        isRunning -> MonitoringCommand.STOP
    else -> MonitoringCommand.NONE
}

/**
 * NT-04 · 홈.
 *
 * Every decision on this screen is delegated: the band to [CravingStage], which prediction becomes
 * the latest state to [LatestPredictionPolicy], the measurement caption to [MeasurementOriginLabel],
 * the camera action to [CameraActionPolicy], and the 대화 권장 marker to [AlertActionPolicy]. The
 * screen renders the result and branches on nothing.
 *
 * Watch connection is never inferred here either — [WatchConnectionTracker] requires two consecutive
 * empty node queries at least three seconds apart before it reports a confirmed disconnection, and a
 * failed query is `ERROR`, not disconnection.
 */
class HomeViewModel(
    private val app: NeuroTruthApp,
) : ViewModel() {

    private val profileRepository = ProfileRepository(app.apiClient, app.endpoints)
    private val rppgRepository = RppgRepository(app.apiClient, app.endpoints, RppgJobStore(app))
    private val watchTracker = WatchConnectionTracker()
    private val controlMessageListener = MessageClient.OnMessageReceivedListener { event ->
        MeasurementControlState.acceptStatus(event.path, event.data)
    }

    private var latest: CravingPrediction? = null
    private var gates: ConsentGates = ConsentGates(app.apiClient.consent())
    private var rppgServiceReady: Boolean = false

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        Wearable.getMessageClient(app).addListener(controlMessageListener)
        refresh()
        observeMonitoring()
        pollWatchConnection()
    }

    /**
     * The services layer owns the SSE stream and the foreground-service state; Home only renders
     * what it publishes. Predictions still go through [onPrediction] so a camera result and a Watch
     * result are ordered by the same policy no matter which arrives first.
     */
    private fun observeMonitoring() {
        viewModelScope.launch {
            MonitoringState.latestPrediction.collect { prediction ->
                prediction?.let(::onPrediction)
            }
        }
        viewModelScope.launch {
            MonitoringState.blocker.collect { blocker ->
                _state.update { it.copy(monitoringNotice = blocker.notice()) }
            }
        }
        viewModelScope.launch {
            MonitoringState.droppedNotice.collect { notice ->
                _state.update { it.copy(droppedNotice = notice) }
            }
        }
        viewModelScope.launch {
            MonitoringState.running.collect { running ->
                _state.update { it.copy(monitoringRunning = running) }
                recompute()
            }
        }
        viewModelScope.launch {
            MeasurementControlState.snapshot.collect { snapshot ->
                _state.update { it.copy(measurementControl = snapshot) }
                reconcileMonitoring(watchTracker.current(), snapshot.status)
            }
        }
    }

    /**
     * Connection is read from the Wear Data Layer connected-node list and nowhere else — no API
     * field or endpoint carries it. [WatchConnectionTracker] converts the raw counts into the Home
     * state, so a failed query lands on `ERROR` rather than being mistaken for a disconnection.
     */
    /** True while the Home screen is on-screen; the poll pauses off-tab and while backgrounded. */
    @Volatile
    private var screenActive: Boolean = false

    fun onScreenActive(active: Boolean) {
        screenActive = active
        if (active) recompute()
    }

    private fun pollWatchConnection() {
        viewModelScope.launch {
            while (isActive) {
                if (screenActive) queryWatchNodes()
                delay(WATCH_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun queryWatchNodes() {
        withContext(Dispatchers.IO) {
            runCatching { Tasks.await(Wearable.getNodeClient(app).connectedNodes).size }
        }
            .onSuccess { onWatchNodesQueried(it) }
            .onFailure { onWatchQueryFailed() }
    }

    /** Returning to the foreground re-confirms the connection before the camera action reopens. */
    fun onResumed() {
        onWatchPeerEvent()
        // Consent and service readiness can have changed while the app was away; the initial load
        // is already in flight on the first resume, so it is not repeated.
        if (!_state.value.isLoading) refresh()
        viewModelScope.launch { queryWatchNodes() }
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = profileRepository.me()
                    gates = ConsentGates(snapshot.consent)
                    rppgServiceReady = rppgRepository.isReady()
                    // The stored camera result is offered, not imposed: LatestPredictionPolicy
                    // keeps a newer Watch result in front of it.
                    snapshot to rppgRepository.latestResult(snapshot.userId)?.toPrediction()
                }
            }
            outcome
                .onSuccess { (snapshot, cameraPrediction) ->
                    _state.update {
                        it.copy(
                            displayName = snapshot.displayName,
                            accountSummary = "${snapshot.email} · 내 계정과 동의 상태",
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                    cameraPrediction?.let(::onPrediction)
                    recompute()
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "서버에 연결하지 못했어요. 아래로 당겨 다시 시도해 주세요.",
                        )
                    }
                    recompute()
                }
        }
    }

    /**
     * Applies a prediction from the upload response or the SSE stream.
     *
     * A late-arriving older camera result must not displace a newer Watch result, and camera results
     * stay in dashboard history regardless of what happens to the Home latest state.
     */
    fun onPrediction(prediction: CravingPrediction) {
        if (!LatestPredictionPolicy.shouldReplace(latest, prediction)) return
        latest = prediction
        recompute()
    }

    fun onWatchNodesQueried(connectedNodeCount: Int, nowMs: Long = System.currentTimeMillis()) {
        watchTracker.onNodesQueried(nowMs, connectedNodeCount)
        if (watchTracker.current() == WatchConnectionState.DISCONNECTED) {
            MeasurementControlState.reset()
        }
        recompute()
    }

    fun onWatchQueryFailed() {
        watchTracker.onQueryFailed()
        recompute()
    }

    fun onWatchPeerEvent() {
        watchTracker.onPeerEvent()
        recompute()
    }

    /** Hidden 2.5-second long press on the profile header. No affordance exists on the screen. */
    fun onDeveloperEntryUnlocked() =
        _state.update { it.copy(developerEntryUnlocked = true) }

    fun onDeveloperEntryDismissed() =
        _state.update { it.copy(developerEntryUnlocked = false) }

    /** Explicit patient action; connection alone never starts either service. */
    fun requestMeasurement(start: Boolean) {
        if (watchTracker.current() != WatchConnectionState.CONNECTED) return
        if (!gates.canUploadBiosignal) return
        if (start) {
            val canPostNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!canPostNotification) {
                MonitoringState.setBlocker(MonitoringBlocker.NOTIFICATION_PERMISSION_REVOKED)
                return
            }
        } else {
            MonitoringService.stop(app)
        }
        viewModelScope.launch(Dispatchers.IO) {
            MonitoringService.requestMeasurement(app, start)
        }
    }

    private fun recompute() {
        val watchState = watchTracker.current()
        val cameraPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val entry = HomeCameraEntryPolicy.resolve(
            CameraActionPolicy.resolve(
                watchState = watchState,
                gates = gates,
                cameraPermissionGranted = cameraPermission,
                rppgServiceReady = rppgServiceReady,
            ),
        )
        val current = latest
        val showCraving = gates.canReceiveAiPrediction || gates.consent == null
        val stage = if (showCraving) current?.stage else null

        _state.update {
            it.copy(
                stage = stage,
                stageMessage = stage?.message.orEmpty(),
                measurementOrigin = current?.takeIf { showCraving }?.let(::originLabel),
                recommendsConversation = showCraving &&
                    AlertActionPolicy.recommendsConversation(current?.alertAction),
                cravingHiddenReason = if (showCraving) {
                    null
                } else {
                    "설정에서 AI 분석 동의를 켜면 갈망 상태를 볼 수 있어요."
                },
                watchState = watchState,
                cameraEnabled = entry.enabled,
                cameraNotice = entry.notice,
            )
        }
        if (screenActive) {
            reconcileMonitoring(watchState, _state.value.measurementControl.status)
        }
    }

    /**
     * The Home connection poll is the process-level trigger for the long-lived monitoring service.
     * Previously it only rendered "연결됨", leaving both sensor uploads and prediction SSE stopped.
     */
    private fun reconcileMonitoring(
        watchState: WatchConnectionState,
        watchStatus: MeasurementControlStatus,
    ) {
        when (
            monitoringCommand(
                watchState = watchState,
                canUploadBiosignal = gates.canUploadBiosignal,
                watchStatus = watchStatus,
                isRunning = MonitoringState.running.value,
            )
        ) {
            MonitoringCommand.START -> {
                val canPostNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (canPostNotification) {
                    MonitoringService.start(app, watchState, gates)
                } else {
                    MonitoringState.setBlocker(MonitoringBlocker.NOTIFICATION_PERMISSION_REVOKED)
                }
            }

            MonitoringCommand.STOP -> MonitoringService.stop(app)
            MonitoringCommand.NONE -> Unit
        }
    }

    override fun onCleared() {
        Wearable.getMessageClient(app).removeListener(controlMessageListener)
        super.onCleared()
    }

    /** `실시간 · Watch` while the Watch is streaming; otherwise the stored timestamp and device. */
    private fun originLabel(prediction: CravingPrediction): String {
        val isLive = prediction.source == SOURCE_WATCH &&
            System.currentTimeMillis() - prediction.timestampMs <= LIVE_WINDOW_MS
        if (isLive) return MeasurementOriginLabel.LIVE_WATCH
        return MeasurementOriginLabel.stored(
            TIMESTAMP_FORMAT.format(Date(prediction.timestampMs)),
            prediction.source,
        )
    }

    companion object {
        private const val LIVE_WINDOW_MS = 30_000L

        /** Matches the confirmation window [WatchConnectionTracker] needs to leave CHECKING. */
        private const val WATCH_POLL_INTERVAL_MS = 3_000L

        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(app) as T
            }
    }
}
