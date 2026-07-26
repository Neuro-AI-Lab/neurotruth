package com.neurotruth.mobile.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.WatchConnectionState
import com.neurotruth.mobile.core.WatchConnectionTracker
import com.neurotruth.mobile.data.CravingCalendar
import com.neurotruth.mobile.data.CravingDashboard
import com.neurotruth.mobile.data.CravingSeries
import com.neurotruth.mobile.data.CravingSeriesPoint
import com.neurotruth.mobile.data.DashboardRepository
import com.neurotruth.mobile.data.LivePpgSource
import com.neurotruth.mobile.data.LivePpgState
import com.neurotruth.mobile.data.PpgPreviewResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class DashboardUiState(
    val isLoading: Boolean = true,
    val timezone: String = "",
    val series: CravingSeries? = null,
    val seriesError: String? = null,
    val selectedSeriesAtMs: Long? = null,
    val dashboard: CravingDashboard? = null,
    val dashboardError: String? = null,
    val calendarView: String = DashboardRepository.CALENDAR_VIEW_DAY,
    val calendarAnchor: LocalDate = LocalDate.now(),
    val calendar: CravingCalendar? = null,
    val calendarError: String? = null,
    val selectedCalendarIndex: Int? = null,
    val eventRange: String = DashboardRepository.EVENT_RANGE_7D,
    val auqRange: String = DashboardRepository.AUQ_RANGE_TODAY,
    val selectedHour: Int? = null,
    val selectedEventIndex: Int? = null,
    val selectedAuqIndex: Int? = null,
    /** Section 5a. Rebuilt from the watch buffer on every poll; never cached or persisted. */
    val livePpg: LivePpgState = LivePpgState.Waiting,
    val watchState: WatchConnectionState = WatchConnectionState.CHECKING,
    /** Section 5b. Held only for the life of this screen; the preview is never cached or persisted. */
    val selectedPredictionId: String? = null,
    val ppg: PpgPreviewResult? = null,
    val isPpgLoading: Boolean = false,
    val ppgError: String? = null,
) {
    val hasAnyError: Boolean
        get() = seriesError != null || dashboardError != null || calendarError != null

    /** No point carries a `predictionId`, so there is nothing the user could select. */
    val hasPpgSelectionPath: Boolean get() = series?.hasSelectablePoints == true
}

/**
 * NT-08 · 대시보드.
 *
 * Five sections in one scroll. The patient dashboard deliberately renders no state-inference card
 * and no report-status card — the backend keeps those contracts for the clinician console, and
 * showing them here would present a research inference as a personal verdict.
 *
 * The recent-hour series and the aggregate are separate requests so one failing never blanks the
 * other, and so the recent hour is never satisfied by truncating a wider range.
 */
class DashboardViewModel(
    private val app: NeuroTruthApp,
) : ViewModel() {

    private val repository = DashboardRepository(app.apiClient, app.endpoints)

    private val livePpgSource = LivePpgSource()

    private val watchTracker = WatchConnectionTracker()

    private val _state = MutableStateFlow(
        DashboardUiState(timezone = ZoneId.systemDefault().id),
    )
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        refresh()
        observeLivePpg()
    }

    /**
     * Section 5a · the live watch trace.
     *
     * The loop is scoped to this ViewModel, so it stops when the dashboard leaves the back stack —
     * this is a view of a buffer the services layer already keeps, and it starts nothing of its own.
     * A failed node query is [WatchConnectionState.ERROR], never disconnection, exactly as on Home.
     */
    /** True while the dashboard is on-screen; the live-PPG poll pauses off-tab and while backgrounded. */
    @Volatile
    private var screenActive: Boolean = true

    fun onScreenActive(active: Boolean) {
        screenActive = active
    }

    private fun observeLivePpg() {
        viewModelScope.launch {
            while (isActive) {
                if (!screenActive) {
                    delay(LIVE_POLL_INTERVAL_MS)
                    continue
                }
                val connectedNodes = withContext(Dispatchers.IO) {
                    runCatching { Tasks.await(Wearable.getNodeClient(app).connectedNodes).size }
                }
                val watchState = connectedNodes
                    .map { watchTracker.onNodesQueried(System.currentTimeMillis(), it) }
                    .getOrElse { watchTracker.onQueryFailed() }

                val trace = livePpgSource.trace(System.currentTimeMillis())
                val live = when {
                    // Fresh samples are proof of streaming even while the node query is still
                    // settling; a stale or empty buffer never becomes a waveform.
                    trace != null -> LivePpgState.Streaming(trace)
                    watchState == WatchConnectionState.DISCONNECTED -> LivePpgState.Unavailable
                    else -> LivePpgState.Waiting
                }
                _state.update { it.copy(livePpg = live, watchState = watchState) }
                delay(LIVE_POLL_INTERVAL_MS)
            }
        }
    }

    fun refresh() {
        val current = _state.value
        load(current.eventRange, current.auqRange)
    }

    fun onCalendarViewChanged(view: String) {
        if (view !in setOf(
                DashboardRepository.CALENDAR_VIEW_DAY,
                DashboardRepository.CALENDAR_VIEW_WEEK,
                DashboardRepository.CALENDAR_VIEW_MONTH,
            ) || view == _state.value.calendarView
        ) {
            return
        }
        _state.update {
            it.copy(calendarView = view, selectedCalendarIndex = null, calendarError = null)
        }
        loadCalendar()
    }

    fun onCalendarPrevious() {
        _state.update {
            val anchor = when (it.calendarView) {
                DashboardRepository.CALENDAR_VIEW_DAY -> it.calendarAnchor.minusDays(1)
                DashboardRepository.CALENDAR_VIEW_WEEK -> it.calendarAnchor.minusDays(7)
                else -> it.calendarAnchor.minusMonths(1)
            }
            it.copy(calendarAnchor = anchor, selectedCalendarIndex = null)
        }
        loadCalendar()
    }

    fun onCalendarNext() {
        _state.update {
            val anchor = when (it.calendarView) {
                DashboardRepository.CALENDAR_VIEW_DAY -> it.calendarAnchor.plusDays(1)
                DashboardRepository.CALENDAR_VIEW_WEEK -> it.calendarAnchor.plusDays(7)
                else -> it.calendarAnchor.plusMonths(1)
            }
            it.copy(calendarAnchor = anchor, selectedCalendarIndex = null)
        }
        loadCalendar()
    }

    fun onCalendarAnchorChanged(anchor: LocalDate) {
        _state.update {
            it.copy(calendarAnchor = anchor, selectedCalendarIndex = null, calendarError = null)
        }
        loadCalendar()
    }

    fun onCalendarBucketSelected(index: Int?) {
        _state.update {
            it.copy(selectedCalendarIndex = if (it.selectedCalendarIndex == index) null else index)
        }
    }

    fun onEventRangeChanged(range: String) {
        if (range == _state.value.eventRange) return
        _state.update { it.copy(eventRange = range, selectedEventIndex = null) }
        load(range, _state.value.auqRange)
    }

    fun onAuqRangeChanged(range: String) {
        if (range == _state.value.auqRange) return
        _state.update { it.copy(auqRange = range, selectedAuqIndex = null) }
        load(_state.value.eventRange, range)
    }

    /** Tapping the selected bar again clears the detail card. */
    fun onHourSelected(hour: Int?) {
        _state.update { it.copy(selectedHour = if (it.selectedHour == hour) null else hour) }
    }

    fun onEventSelected(index: Int?) {
        _state.update {
            it.copy(selectedEventIndex = if (it.selectedEventIndex == index) null else index)
        }
    }

    fun onAuqSelected(index: Int?) {
        _state.update {
            it.copy(selectedAuqIndex = if (it.selectedAuqIndex == index) null else index)
        }
    }

    /**
     * Section 5 · PPG.
     *
     * The preview belongs to one selected prediction, not to a standing feed, so every selection is
     * a fresh request and nothing is kept between selections or across screens.
     */
    fun onPredictionSelected(predictionId: String?) {
        if (predictionId == null || predictionId == _state.value.selectedPredictionId) {
            _state.update {
                it.copy(selectedPredictionId = null, ppg = null, ppgError = null, isPpgLoading = false)
            }
            return
        }
        _state.update {
            it.copy(
                selectedPredictionId = predictionId,
                ppg = null,
                ppgError = null,
                isPpgLoading = true,
            )
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { repository.ppgPreview(predictionId) }
            }
            _state.update { state ->
                if (state.selectedPredictionId != predictionId) return@update state
                state.copy(
                    isPpgLoading = false,
                    ppg = outcome.getOrNull(),
                    ppgError = if (outcome.isFailure) PPG_ERROR else null,
                )
            }
        }
    }

    fun onSeriesPointSelected(point: CravingSeriesPoint?) {
        _state.update { it.copy(selectedSeriesAtMs = point?.atMs) }
        onPredictionSelected(point?.predictionId)
    }

    private fun load(eventRange: String, auqRange: String) {
        val timezone = ZoneId.systemDefault().id
        _state.update {
            it.copy(isLoading = true, timezone = timezone, seriesError = null, dashboardError = null)
        }
        viewModelScope.launch {
            val series = withContext(Dispatchers.IO) {
                runCatching { repository.recentHourSeries() }
            }
            val dashboard = withContext(Dispatchers.IO) {
                runCatching { repository.cravingDashboard(timezone, eventRange, auqRange) }
            }
            val currentState = _state.value
            val calendar = withContext(Dispatchers.IO) {
                runCatching {
                    repository.cravingCalendar(
                        timezone = timezone,
                        view = currentState.calendarView,
                        anchor = currentState.calendarAnchor.toString(),
                    )
                }
            }
            _state.update {
                val acceptedCalendar = it.calendarView == currentState.calendarView &&
                    it.calendarAnchor == currentState.calendarAnchor
                it.copy(
                    isLoading = false,
                    series = series.getOrNull(),
                    seriesError = if (series.isFailure) SERIES_ERROR else null,
                    dashboard = dashboard.getOrNull(),
                    dashboardError = if (dashboard.isFailure) DASHBOARD_ERROR else null,
                    calendar = if (acceptedCalendar) calendar.getOrNull() else it.calendar,
                    calendarError = if (!acceptedCalendar) {
                        it.calendarError
                    } else if (calendar.isFailure) {
                        CALENDAR_ERROR
                    } else {
                        null
                    },
                    selectedHour = if (dashboard.isSuccess) it.selectedHour else null,
                    // A refreshed series invalidates the selected measurement; nothing is retained.
                    selectedPredictionId = null,
                    selectedSeriesAtMs = null,
                    ppg = null,
                    ppgError = null,
                    isPpgLoading = false,
                )
            }
        }
    }

    private fun loadCalendar() {
        val current = _state.value
        val timezone = ZoneId.systemDefault().id
        _state.update { it.copy(isLoading = true, calendarError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.cravingCalendar(
                        timezone = timezone,
                        view = current.calendarView,
                        anchor = current.calendarAnchor.toString(),
                    )
                }
            }
            _state.update {
                if (it.calendarView != current.calendarView ||
                    it.calendarAnchor != current.calendarAnchor
                ) {
                    return@update it
                }
                it.copy(
                    isLoading = false,
                    timezone = timezone,
                    calendar = result.getOrNull(),
                    calendarError = if (result.isFailure) CALENDAR_ERROR else null,
                )
            }
        }
    }

    companion object {
        private const val SERIES_ERROR = "최근 1시간 기록을 불러오지 못했어요."
        private const val DASHBOARD_ERROR = "기록을 불러오지 못했어요."
        private const val CALENDAR_ERROR = "선택한 기간의 기록을 불러오지 못했어요."
        private const val PPG_ERROR = "신호 데이터를 불러오지 못했어요."

        /** Roughly the watch's own flush cadence; fast enough to look live, cheap enough to poll. */
        private const val LIVE_POLL_INTERVAL_MS = 1_000L

        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(app) as T
            }
    }
}
