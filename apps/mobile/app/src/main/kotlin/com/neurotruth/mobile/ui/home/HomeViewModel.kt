package com.neurotruth.mobile.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.AlertActionPolicy
import com.neurotruth.mobile.core.CameraActionPolicy
import com.neurotruth.mobile.core.CameraAvailability
import com.neurotruth.mobile.core.ConsentGates
import com.neurotruth.mobile.core.CravingPrediction
import com.neurotruth.mobile.core.CravingStage
import com.neurotruth.mobile.core.LatestPredictionPolicy
import com.neurotruth.mobile.core.MeasurementOriginLabel
import com.neurotruth.mobile.core.SOURCE_WATCH
import com.neurotruth.mobile.core.WatchConnectionState
import com.neurotruth.mobile.core.WatchConnectionTracker
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.data.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

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
    val cameraBlockedReason: String? = CameraActionPolicy.REASON_WATCH_UNKNOWN,

    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val developerEntryUnlocked: Boolean = false,
) {
    val hasMeasurement: Boolean get() = stage != null

    /** Never 0% and never "낮음" — an absent measurement is its own state. */
    val stageLabel: String get() = stage?.label ?: CravingStage.NO_DATA_LABEL

    val cameraEnabled: Boolean get() = cameraBlockedReason == null

    val watchStateLabel: String
        get() = when (watchState) {
            WatchConnectionState.CONNECTED -> "연결됨"
            WatchConnectionState.DISCONNECTED -> "연결 안 됨"
            WatchConnectionState.CHECKING -> "확인 중"
            WatchConnectionState.ERROR -> "상태를 확인할 수 없어요"
        }
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
    private val watchTracker = WatchConnectionTracker()

    private var latest: CravingPrediction? = null
    private var gates: ConsentGates = ConsentGates(app.apiClient.consent())
    private var rppgServiceReady: Boolean = false

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = profileRepository.me()
                    gates = ConsentGates(snapshot.consent)
                    rppgServiceReady = queryRppgReady()
                    snapshot
                }
            }
            outcome
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            displayName = snapshot.displayName,
                            accountSummary = "${snapshot.email} · 내 계정과 동의 상태",
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
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

    private fun recompute() {
        val watchState = watchTracker.current()
        val cameraPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val availability = CameraActionPolicy.resolve(
            watchState = watchState,
            gates = gates,
            cameraPermissionGranted = cameraPermission,
            rppgServiceReady = rppgServiceReady,
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
                cameraBlockedReason = (availability as? CameraAvailability.Blocked)?.reason,
            )
        }
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

    /** All three flags together are the only reading of `ready`. */
    private fun queryRppgReady(): Boolean = runCatching {
        val response = app.apiClient.execute(ApiRequest("GET", app.endpoints.rppgStatus))
        if (!response.isSuccessful) return false
        val json = JSONObject(response.body)
        json.optBoolean("enabled") && json.optBoolean("available") && json.optBoolean("modelLoaded")
    }.getOrDefault(false)

    companion object {
        private const val LIVE_WINDOW_MS = 30_000L
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(app) as T
            }
    }
}
