package com.example.healthsensor

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class DashboardRange(val wire: String, val label: String) {
    HOURS_24("24h", "24시간"),
    DAYS_7("7d", "7일"),
    DAYS_30("30d", "30일")
}

enum class ProbabilityRange(
    val wire: String,
    val label: String,
    val durationMs: Long,
    val bucketSeconds: Int,
    val maxPoints: Int
) {
    HOUR_1("1h", "최근 1시간", 60 * 60 * 1_000L, 10, 360),
    MINUTES_10("10m", "실시간 10분", 10 * 60 * 1_000L, 1, 600),
    HOURS_24("24h", "24시간", 24 * 60 * 60 * 1_000L, 60, 1_440),
    DAYS_7("7d", "7일", 7 * 24 * 60 * 60 * 1_000L, 600, 1_008),
    DAYS_30("30d", "30일", 30L * 24 * 60 * 60 * 1_000L, 1_800, 1_440)
}

enum class EventRange(val wire: String, val label: String, val days: Int) {
    DAYS_7("7d", "7일", 7),
    DAYS_30("30d", "30일", 30)
}

enum class AuqRange(val wire: String, val label: String, val bucketCount: Int) {
    TODAY("today", "오늘", 24),
    DAYS_7("7d", "7일", 7),
    DAYS_30("30d", "30일", 30)
}

data class CurrentCraving(val probability: Float, val atMs: Long)

data class CravingStageCounts(
    val low: Int,
    val observe: Int,
    val caution: Int,
    val high: Int
) {
    val total: Int get() = low + observe + caution + high
}

data class HourlyCravingBucket(
    val localStart: String,
    val averageProbability: Float?,
    val minimumProbability: Float?,
    val maximumProbability: Float?,
    val sampleCount: Int,
    val stageCounts: CravingStageCounts
)

data class DailyEventBucket(
    val localDate: String,
    val hasPredictionData: Boolean,
    val recommendCount: Int,
    val requiredCount: Int,
    val totalCount: Int
)

data class AuqBucket(
    val localLabel: String,
    val averageScore: Float?,
    val averageNormalizedScore: Float?,
    val sampleCount: Int
)

data class CravingBarDashboard(
    val timezone: String,
    val generatedAtMs: Long,
    val currentCraving: CurrentCraving?,
    val hourlyCraving: List<HourlyCravingBucket>,
    val eventRange: EventRange,
    val dailyEvents: List<DailyEventBucket>,
    val auqRange: AuqRange,
    val auqBucketUnit: String,
    val auq: List<AuqBucket>
)

data class DashboardPrediction(
    val id: String,
    val atMs: Long,
    val stateClass: String,
    val probability: Float?,
    val cravingProbability: Float?,
    val ppgPreviewAvailable: Boolean
)

data class CravingProbabilityPoint(
    val atMs: Long,
    val probability: Float,
    val sampleCount: Int,
    val predictionId: String? = null
)

data class CravingProbabilitySeries(
    val range: ProbabilityRange,
    val fromMs: Long,
    val toMs: Long,
    val bucketSeconds: Int,
    val points: List<CravingProbabilityPoint>
)

data class DashboardAssessment(
    val id: String,
    val sessionId: String,
    val atMs: Long,
    val rawScore: Float,
    val scaleMin: Float,
    val scaleMax: Float
)

data class DashboardEvent(
    val id: String,
    val type: String,
    val atMs: Long,
    val label: String
)

data class DashboardStateSummary(
    val state: String,
    val confidence: Float?,
    val summaryStatus: String,
    val summary: String?,
    val createdAtMs: Long
)

data class DashboardReport(val sessionId: String, val status: String, val updatedAtMs: Long)

data class PatientDashboardData(
    val range: DashboardRange,
    val predictions: List<DashboardPrediction>,
    val assessments: List<DashboardAssessment>,
    val events: List<DashboardEvent>,
    val latestState: DashboardStateSummary?,
    val longitudinalState: DashboardStateSummary?,
    val reports: List<DashboardReport>
)

data class PpgPreviewSample(val atMs: Long, val value: Float)
data class PpgPreview(val predictionId: String, val samplingHz: Float, val samples: List<PpgPreviewSample>)

class PatientDashboardApi(private val client: AuthenticatedApiClient) {
    fun get(range: DashboardRange): PatientDashboardData {
        val response = client.executeAuthenticated(ApiRequest("GET", client.endpoints.dashboard(range.wire)))
        if (response.statusCode !in 200..299) throw ApiHttpException(response.statusCode)
        return PatientDashboardParser.parse(response.body)
    }

    fun getPpgPreview(predictionId: String): PpgPreview {
        val response = client.executeAuthenticated(ApiRequest("GET", client.endpoints.ppgPreview(predictionId)))
        if (response.statusCode !in 200..299) throw ApiHttpException(response.statusCode)
        return PatientDashboardParser.parsePpgPreview(response.body)
    }

    fun getCravingProbabilitySeries(range: ProbabilityRange): CravingProbabilitySeries {
        val response = client.executeAuthenticated(
            ApiRequest("GET", client.endpoints.cravingProbabilitySeries(range.wire))
        )
        if (response.statusCode !in 200..299) throw ApiHttpException(response.statusCode)
        return PatientDashboardParser.parseProbabilitySeries(response.body)
    }

    fun getCravingDashboard(
        timezone: String,
        eventRange: EventRange,
        auqRange: AuqRange
    ): CravingBarDashboard {
        val response = client.executeAuthenticated(
            ApiRequest(
                "GET",
                client.endpoints.cravingDashboard(timezone, eventRange.wire, auqRange.wire)
            )
        )
        if (response.statusCode !in 200..299) throw ApiHttpException(response.statusCode)
        return PatientDashboardParser.parseCravingDashboard(response.body)
    }
}

object PatientDashboardParser {
    fun parse(body: String): PatientDashboardData {
        val root = JSONObject(body)
        val range = DashboardRange.values().singleOrNull { it.wire == root.optString("range") }
            ?: throw IllegalArgumentException("invalid dashboard range")
        return PatientDashboardData(
            range = range,
            predictions = root.optJSONArray("predictions").objects().map { item ->
                DashboardPrediction(
                    id = item.uuid("predictionId"),
                    atMs = item.instant("at"),
                    stateClass = item.optString("class", "unknown").lowercase(),
                    probability = item.floatOrNull("probability"),
                    cravingProbability = item.floatOrNull("cravingProbability")?.probabilityOrNull(),
                    ppgPreviewAvailable = item.optBoolean("ppgPreviewAvailable", false)
                )
            }.sortedBy(DashboardPrediction::atMs),
            assessments = root.optJSONArray("assessments").objects().map { item ->
                DashboardAssessment(
                    id = item.uuid("assessmentId"),
                    sessionId = item.uuid("sessionId"),
                    atMs = item.instant("at"),
                    rawScore = item.floatOrNull("rawScore") ?: 0f,
                    scaleMin = item.floatOrNull("scaleMin") ?: 0f,
                    scaleMax = item.floatOrNull("scaleMax") ?: 48f
                )
            }.sortedBy(DashboardAssessment::atMs),
            events = root.optJSONArray("events").objects().map { item ->
                DashboardEvent(
                    id = item.optString("eventId"),
                    type = item.optString("type"),
                    atMs = item.instant("at"),
                    label = item.optString("label")
                )
            }.sortedBy(DashboardEvent::atMs),
            latestState = root.optJSONObject("latestState")?.toState(),
            longitudinalState = root.optJSONObject("longitudinalState")?.toState(),
            reports = root.optJSONArray("reports").objects().map { item ->
                DashboardReport(
                    sessionId = item.uuid("sessionId"),
                    status = item.optString("status"),
                    updatedAtMs = item.instant("updatedAt")
                )
            }.sortedBy(DashboardReport::updatedAtMs)
        )
    }

    fun parsePpgPreview(body: String): PpgPreview {
        val root = JSONObject(body)
        val samples = root.optJSONArray("samples").objects().map { item ->
            PpgPreviewSample(item.instant("at"), item.floatOrNull("value") ?: error("PPG value missing"))
        }
        require(samples.size <= 512) { "PPG preview exceeds 512 points" }
        require(samples.zipWithNext().all { it.first.atMs <= it.second.atMs }) { "PPG preview must be chronological" }
        return PpgPreview(
            predictionId = root.uuid("predictionId"),
            samplingHz = root.floatOrNull("samplingHz") ?: error("samplingHz missing"),
            samples = samples
        )
    }

    fun parseProbabilitySeries(body: String): CravingProbabilitySeries {
        val root = JSONObject(body)
        val range = ProbabilityRange.values().singleOrNull { it.wire == root.optString("range") }
            ?: throw IllegalArgumentException("invalid probability range")
        val bucketSeconds = root.optInt("bucketSeconds", -1)
        require(bucketSeconds == range.bucketSeconds) { "invalid probability bucket" }
        val points = root.optJSONArray("points").objects().map { item ->
            CravingProbabilityPoint(
                atMs = item.instant("at"),
                probability = (item.floatOrNull("averageCravingProbability")
                    ?: error("averageCravingProbability missing")).requireProbability(),
                sampleCount = item.optInt("sampleCount", 0).also { require(it > 0) },
                predictionId = item.optString("predictionId").takeIf(String::isNotBlank)
            )
        }.sortedBy(CravingProbabilityPoint::atMs)
        require(points.size <= range.maxPoints) { "probability series exceeds range limit" }
        return CravingProbabilitySeries(
            range = range,
            fromMs = root.instant("from"),
            toMs = root.instant("to"),
            bucketSeconds = bucketSeconds,
            points = points
        )
    }

    fun parseCravingDashboard(body: String): CravingBarDashboard {
        val root = JSONObject(body)
        val timezone = root.optString("timezone").trim().also { require(it.isNotBlank()) }
        val current = root.optJSONObject("currentCraving")?.let { item ->
            if (item.isNull("probability") || item.isNull("at")) null else CurrentCraving(
                probability = (item.floatOrNull("probability") ?: error("current probability missing"))
                    .requireProbability(),
                atMs = item.instant("at")
            )
        }
        val hourly = root.optJSONObject("hourlyCraving")
            ?.optJSONArray("buckets")
            .objects()
            .map { item ->
                val sampleCount = item.optInt("sampleCount", 0).also { require(it >= 0) }
                val counts = item.getJSONObject("stageCounts").let { stages ->
                    CravingStageCounts(
                        low = stages.optInt("low", -1).also { require(it >= 0) },
                        observe = stages.optInt("observe", -1).also { require(it >= 0) },
                        caution = stages.optInt("caution", -1).also { require(it >= 0) },
                        high = stages.optInt("high", -1).also { require(it >= 0) }
                    )
                }
                HourlyCravingBucket(
                    localStart = item.getString("localStart").also { OffsetDateTime.parse(it) },
                    averageProbability = item.floatOrNull("averageProbability")?.requireProbability(),
                    minimumProbability = item.floatOrNull("minimumProbability")?.requireProbability(),
                    maximumProbability = item.floatOrNull("maximumProbability")?.requireProbability(),
                    sampleCount = sampleCount,
                    stageCounts = counts
                ).also { bucket ->
                    require(bucket.stageCounts.total == sampleCount)
                    require(
                        sampleCount == 0 ||
                            listOf(
                                bucket.averageProbability,
                                bucket.minimumProbability,
                                bucket.maximumProbability
                            ).all { it != null }
                    )
                }
            }
        require(hourly.size == 24) { "hourly craving must contain 24 wall-clock buckets" }

        val dailyEvents = root.getJSONObject("dailyEvents")
        val eventRange = EventRange.values().singleOrNull {
            it.wire == dailyEvents.optString("range")
        } ?: throw IllegalArgumentException("invalid event range")
        val eventBuckets = dailyEvents.optJSONArray("buckets").objects().map { item ->
            DailyEventBucket(
                localDate = item.getString("localDate").also { LocalDate.parse(it) },
                hasPredictionData = item.getBoolean("hasPredictionData"),
                recommendCount = item.optInt("recommendCount", 0).also { require(it >= 0) },
                requiredCount = item.optInt("requiredCount", 0).also { require(it >= 0) },
                totalCount = item.optInt("totalCount", 0).also { require(it >= 0) }
            ).also { require(it.totalCount == it.recommendCount + it.requiredCount) }
        }
        require(eventBuckets.size == eventRange.days) { "event bucket count mismatch" }

        val auqObject = root.getJSONObject("auq")
        val auqRange = AuqRange.values().singleOrNull {
            it.wire == auqObject.optString("range")
        } ?: throw IllegalArgumentException("invalid AUQ range")
        val bucketUnit = auqObject.getString("bucketUnit")
        require(bucketUnit == if (auqRange == AuqRange.TODAY) "hour" else "day")
        val auqBuckets = auqObject.optJSONArray("buckets").objects().map { item ->
            val label = if (auqRange == AuqRange.TODAY) {
                item.getString("localStart").also { OffsetDateTime.parse(it) }
            } else {
                item.getString("localDate").also { LocalDate.parse(it) }
            }
            AuqBucket(
                localLabel = label,
                averageScore = item.floatOrNull("averageScore")?.let { value ->
                    require(value in 0f..48f) { "AUQ average score must be between 0 and 48" }
                    value
                } ?: item.floatOrNull("averageNormalizedScore")
                    ?.requireProbability()
                    ?.times(48f),
                averageNormalizedScore = item.floatOrNull("averageNormalizedScore")?.requireProbability(),
                sampleCount = item.optInt("sampleCount", 0).also { require(it >= 0) }
            ).also { require(it.sampleCount == 0 || it.averageScore != null) }
        }
        require(auqBuckets.size == auqRange.bucketCount) { "AUQ bucket count mismatch" }

        return CravingBarDashboard(
            timezone = timezone,
            generatedAtMs = root.instant("generatedAt"),
            currentCraving = current,
            hourlyCraving = hourly,
            eventRange = eventRange,
            dailyEvents = eventBuckets,
            auqRange = auqRange,
            auqBucketUnit = bucketUnit,
            auq = auqBuckets
        )
    }

    private fun JSONObject.toState() = DashboardStateSummary(
        state = optString("state", "unknown"),
        confidence = floatOrNull("confidence"),
        summaryStatus = optString("summaryStatus", "pending"),
        summary = if (!has("summary") || isNull("summary")) null else optString("summary").takeIf(String::isNotBlank),
        createdAtMs = instant("createdAt")
    )

    private fun JSONArray?.objects(): List<JSONObject> {
        this ?: return emptyList()
        return (0 until length()).mapNotNull(::optJSONObject)
    }

    private fun JSONObject.uuid(key: String): String = UUID.fromString(getString(key)).toString()
    private fun JSONObject.instant(key: String): Long = Instant.parse(getString(key)).toEpochMilli()
    private fun JSONObject.floatOrNull(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).toFloat().takeIf(Float::isFinite)
    }
}

internal object CravingProbabilitySeriesState {
    fun appendLive(
        current: CravingProbabilitySeries,
        prediction: CravingPrediction
    ): CravingProbabilitySeries {
        if (current.range != ProbabilityRange.HOUR_1) return current
        val probability = prediction.cravingProbability?.probabilityOrNull() ?: return current
        val point = CravingProbabilityPoint(
            atMs = prediction.timestampMs,
            probability = probability,
            sampleCount = 1,
            predictionId = prediction.predictionId
        )
        val updatedTo = maxOf(current.toMs, point.atMs)
        val updatedFrom = updatedTo - current.range.durationMs
        val retained = current.points.filterNot { existing ->
            existing.atMs == point.atMs || (
                point.predictionId != null && existing.predictionId == point.predictionId
            )
        }
        val points = (retained + point)
            .filter { it.atMs in updatedFrom..updatedTo }
            .sortedBy(CravingProbabilityPoint::atMs)
            .takeLast(current.range.maxPoints)
        return current.copy(fromMs = updatedFrom, toMs = updatedTo, points = points)
    }

    fun segments(series: CravingProbabilitySeries): List<List<CravingProbabilityPoint>> {
        if (series.points.isEmpty()) return emptyList()
        val maxContinuousGapMs = series.bucketSeconds * 1_500L
        val segments = mutableListOf<MutableList<CravingProbabilityPoint>>()
        series.points.sortedBy(CravingProbabilityPoint::atMs).forEach { point ->
            val current = segments.lastOrNull()
            if (current == null || point.atMs - current.last().atMs > maxContinuousGapMs) {
                segments += mutableListOf(point)
            } else {
                current += point
            }
        }
        return segments
    }
}

private fun Float.requireProbability(): Float {
    require(isFinite() && this in 0f..1f) { "probability must be between zero and one" }
    return this
}

private fun Float.probabilityOrNull(): Float? = takeIf { it.isFinite() && it in 0f..1f }

class PatientDashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val api = PatientDashboardApi(MobileApiProvider.get(application))
    private val _range = MutableStateFlow(DashboardRange.HOURS_24)
    val range: StateFlow<DashboardRange> = _range
    private val _data = MutableStateFlow<PatientDashboardData?>(null)
    val data: StateFlow<PatientDashboardData?> = _data
    private val _preview = MutableStateFlow<PpgPreview?>(null)
    val preview: StateFlow<PpgPreview?> = _preview
    private val _status = MutableStateFlow("기록을 불러오는 중입니다")
    val status: StateFlow<String> = _status
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _probabilityRange = MutableStateFlow(ProbabilityRange.HOUR_1)
    val probabilityRange: StateFlow<ProbabilityRange> = _probabilityRange
    private val _probabilitySeries = MutableStateFlow<CravingProbabilitySeries?>(null)
    val probabilitySeries: StateFlow<CravingProbabilitySeries?> = _probabilitySeries
    private val _probabilityStatus = MutableStateFlow("갈망 가능성을 불러오는 중입니다")
    val probabilityStatus: StateFlow<String> = _probabilityStatus
    private val _probabilityLoading = MutableStateFlow(false)
    val probabilityLoading: StateFlow<Boolean> = _probabilityLoading
    private val _eventRange = MutableStateFlow(EventRange.DAYS_7)
    val eventRange: StateFlow<EventRange> = _eventRange
    private val _auqRange = MutableStateFlow(AuqRange.TODAY)
    val auqRange: StateFlow<AuqRange> = _auqRange
    private val _barDashboard = MutableStateFlow<CravingBarDashboard?>(null)
    val barDashboard: StateFlow<CravingBarDashboard?> = _barDashboard
    private val _barDashboardStatus = MutableStateFlow("시간별 갈망 기록을 불러오는 중입니다")
    val barDashboardStatus: StateFlow<String> = _barDashboardStatus
    private val _barDashboardLoading = MutableStateFlow(false)
    val barDashboardLoading: StateFlow<Boolean> = _barDashboardLoading

    init {
        viewModelScope.launch {
            PhoneMonitoringState.latestPrediction.collect { prediction ->
                prediction ?: return@collect
                _probabilitySeries.value?.let { current ->
                    _probabilitySeries.value = CravingProbabilitySeriesState.appendLive(current, prediction)
                }
                val probability = prediction.cravingProbability?.probabilityOrNull() ?: return@collect
                _barDashboard.value = _barDashboard.value?.copy(
                    currentCraving = CurrentCraving(probability, prediction.timestampMs)
                )
            }
        }
    }

    fun load(range: DashboardRange = _range.value) {
        if (!MobileAuthRuntime.state.value.authenticated || _loading.value) return
        _range.value = range
        _loading.value = true
        _status.value = "기록을 불러오는 중입니다"
        _preview.value = null
        loadCravingDashboard()
        loadProbability(ProbabilityRange.HOUR_1)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.get(range) } }
                .onSuccess {
                    _data.value = it
                    _status.value = "최근 기록"
                }
                .onFailure { _status.value = "기록을 불러오지 못했습니다" }
            _loading.value = false
        }
    }

    fun selectEventRange(range: EventRange) {
        if (_eventRange.value == range) return
        _eventRange.value = range
        loadCravingDashboard()
    }

    fun selectAuqRange(range: AuqRange) {
        if (_auqRange.value == range) return
        _auqRange.value = range
        loadCravingDashboard()
    }

    fun loadCravingDashboard() {
        if (!MobileAuthRuntime.state.value.authenticated) return
        val requestedEventRange = _eventRange.value
        val requestedAuqRange = _auqRange.value
        _barDashboardLoading.value = true
        _barDashboardStatus.value = "시간별 갈망 기록을 불러오는 중입니다"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.getCravingDashboard(
                        timezone = ZoneId.systemDefault().id,
                        eventRange = requestedEventRange,
                        auqRange = requestedAuqRange
                    )
                }
            }.onSuccess {
                if (_eventRange.value == requestedEventRange && _auqRange.value == requestedAuqRange) {
                    _barDashboard.value = it
                    _barDashboardStatus.value = "기기 현지시간 기준"
                }
            }.onFailure {
                if (_eventRange.value == requestedEventRange && _auqRange.value == requestedAuqRange) {
                    _barDashboardStatus.value = "갈망 요약을 불러오지 못했습니다"
                }
            }
            if (_eventRange.value == requestedEventRange && _auqRange.value == requestedAuqRange) {
                _barDashboardLoading.value = false
            }
        }
    }

    fun loadProbability(range: ProbabilityRange) {
        if (!MobileAuthRuntime.state.value.authenticated || _probabilityLoading.value) return
        _probabilityRange.value = range
        _probabilityLoading.value = true
        _probabilityStatus.value = "갈망 가능성을 불러오는 중입니다"
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.getCravingProbabilitySeries(range) } }
                .onSuccess {
                    _probabilitySeries.value = it
                    _probabilityStatus.value = if (it.points.isEmpty()) "이 기간에는 예측 기록이 없습니다" else "최근 기록"
                }
                .onFailure {
                    _probabilitySeries.value = null
                    _probabilityStatus.value = "갈망 가능성을 불러오지 못했습니다"
                }
            _probabilityLoading.value = false
        }
    }

    fun loadPreview(predictionId: String) {
        _status.value = "PPG를 불러오는 중입니다"
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.getPpgPreview(predictionId) } }
                .onSuccess {
                    _preview.value = it
                    _status.value = "PPG 미리보기"
                }
                .onFailure {
                    _preview.value = null
                    _status.value = "해당 예측의 PPG를 사용할 수 없습니다"
                }
        }
    }
}

@Composable
fun PatientDashboardScreen(
    viewModel: PatientDashboardViewModel,
    livePpg: List<SensorPoint>,
    onClose: () -> Unit
) {
    val data by viewModel.data.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val status by viewModel.status.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val eventRange by viewModel.eventRange.collectAsState()
    val auqRange by viewModel.auqRange.collectAsState()
    val barDashboard by viewModel.barDashboard.collectAsState()
    val barDashboardStatus by viewModel.barDashboardStatus.collectAsState()
    val barDashboardLoading by viewModel.barDashboardLoading.collectAsState()
    val probabilitySeries by viewModel.probabilitySeries.collectAsState()
    val probabilityStatus by viewModel.probabilityStatus.collectAsState()
    val probabilityLoading by viewModel.probabilityLoading.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF7F8FA)).verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("내 상태 기록", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onClose) { Text("홈") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (loading && data == null) Text("불러오는 중…")

        DashboardCard("최근 1시간 갈망 가능성") {
            Text("0~100% · 10초 단위 · 데이터가 없는 구간은 선을 잇지 않습니다", style = MaterialTheme.typography.bodySmall)
            if (probabilityLoading && probabilitySeries == null) Text("불러오는 중…")
            Text(probabilityStatus, style = MaterialTheme.typography.bodySmall)
            probabilitySeries?.let { CravingProbabilityLineChart(it) }
        }

        DashboardCard("오늘 갈망 단계 구성") {
            val latest = barDashboard?.currentCraving?.probability
            Text(
                text = cravingProbabilityLabel(latest),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "연구용 모델 출력이며 진단이나 임상적 갈망 강도를 의미하지 않습니다.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(barDashboardStatus, style = MaterialTheme.typography.bodySmall)
            if (barDashboardLoading && barDashboard == null) Text("불러오는 중…")
            barDashboard?.let { HourlyCravingBarChart(it.hourlyCraving) }
        }

        DashboardCard("갈망 이벤트") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EventRange.values().forEach { candidate ->
                    if (candidate == eventRange) {
                        Button(onClick = { viewModel.selectEventRange(candidate) }) { Text(candidate.label) }
                    } else {
                        OutlinedButton(onClick = { viewModel.selectEventRange(candidate) }) { Text(candidate.label) }
                    }
                }
            }
            barDashboard?.let { DailyEventBarChart(it.dailyEvents) }
        }

        DashboardCard("AUQ 기록") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AuqRange.values().forEach { candidate ->
                    if (candidate == auqRange) {
                        Button(onClick = { viewModel.selectAuqRange(candidate) }) { Text(candidate.label) }
                    } else {
                        OutlinedButton(onClick = { viewModel.selectAuqRange(candidate) }) { Text(candidate.label) }
                    }
                }
            }
            barDashboard?.let { AuqBarChart(it.auq, it.auqRange) }
        }

        DashboardCard("Watch 실시간 PPG") {
            if (livePpg.isEmpty()) Text("Watch PPG를 기다리고 있습니다")
            else SignalLineChart(livePpg.takeLast(250).map { it.value })
        }

        DashboardCard("과거 예측 PPG") {
            val predictions = data?.predictions.orEmpty()
            val prediction = predictions.lastOrNull { it.ppgPreviewAvailable }
            if (prediction == null) {
                Text("미리 볼 수 있는 PPG 기록이 없습니다")
            } else {
                OutlinedButton(onClick = { viewModel.loadPreview(prediction.id) }) { Text("PPG 보기") }
            }
            preview?.let { value ->
                if (value.samples.isEmpty()) Text("PPG 미리보기를 사용할 수 없습니다")
                else SignalLineChart(value.samples.map { it.value })
            }
        }
    }
}

@Composable
private fun DashboardCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun HourlyCravingBarChart(buckets: List<HourlyCravingBucket>) {
    var selected by remember(buckets) { mutableIntStateOf(0) }
    val stages = listOf(
        Triple("안전", Color(0xFF267A73), { value: CravingStageCounts -> value.low }),
        Triple("관찰", Color(0xFF62A8A1), { value: CravingStageCounts -> value.observe }),
        Triple("주의", Color(0xFFD68A22), { value: CravingStageCounts -> value.caution }),
        Triple("심각", Color(0xFFC94C5C), { value: CravingStageCounts -> value.high })
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .semantics { contentDescription = "오늘 시간별 갈망 가능성 단계 구성 막대 그래프" }
            .pointerInput(buckets) {
                detectTapGestures { point ->
                    selected = barIndex(point.x, size.width.toFloat(), buckets.size)
                }
            }
    ) {
        if (buckets.isEmpty()) return@Canvas
        val slot = size.width / buckets.size
        val gap = minOf(slot * 0.25f, 5.dp.toPx())
        buckets.forEachIndexed { index, bucket ->
            val left = index * slot + gap / 2f
            val width = (slot - gap).coerceAtLeast(1f)
            if (bucket.sampleCount == 0) {
                drawRect(
                    Color(0xFFD5D9DE),
                    Offset(left, size.height * 0.82f),
                    androidx.compose.ui.geometry.Size(width, size.height * 0.18f)
                )
            } else {
                var bottom = size.height
                stages.forEach { (_, color, count) ->
                    val height = size.height * count(bucket.stageCounts) / bucket.sampleCount.toFloat()
                    bottom -= height
                    drawRect(
                        color,
                        Offset(left, bottom),
                        androidx.compose.ui.geometry.Size(width, height)
                    )
                }
            }
            if (index == selected) {
                drawRect(
                    Color(0xFF172A46),
                    Offset(left, 0f),
                    androidx.compose.ui.geometry.Size(width, size.height),
                    style = Stroke(1.dp.toPx())
                )
            }
        }
    }
    FixedHourLabels()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        stages.chunked(2).forEach { rowStages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                rowStages.forEach { (label, color, _) ->
                    Row(
                        modifier = Modifier.width(120.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(Modifier.width(10.dp).height(10.dp).background(color))
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    val bucket = buckets.getOrNull(selected) ?: return
    if (bucket.sampleCount == 0) {
        Text("${"%02d".format(selected)}:00–${"%02d".format(selected)}:59 · 데이터 없음")
    } else {
        Text(
            "${"%02d".format(selected)}:00–${"%02d".format(selected)}:59 · " +
                stages.joinToString(" · ") { (label, _, count) ->
                    "$label ${stagePercentage(count(bucket.stageCounts), bucket.sampleCount)}"
                } + " · 표본 ${bucket.sampleCount}개"
        )
    }
}

private fun stagePercentage(count: Int, total: Int): String =
    if (total == 0) "--" else String.format(java.util.Locale.KOREA, "%.1f%%", count * 100f / total)

@Composable
private fun DailyEventBarChart(buckets: List<DailyEventBucket>) {
    var selected by remember(buckets) { mutableIntStateOf(buckets.lastIndex.coerceAtLeast(0)) }
    val maxCount = buckets.maxOfOrNull { it.totalCount }?.coerceAtLeast(1) ?: 1
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .semantics { contentDescription = "일별 갈망 이벤트 막대 그래프" }
            .pointerInput(buckets) {
                detectTapGestures { point ->
                    selected = barIndex(point.x, size.width.toFloat(), buckets.size)
                }
            }
    ) {
        if (buckets.isEmpty()) return@Canvas
        val slot = size.width / buckets.size
        val gap = minOf(slot * 0.25f, 5.dp.toPx())
        buckets.forEachIndexed { index, bucket ->
            val left = index * slot + gap / 2f
            val width = (slot - gap).coerceAtLeast(1f)
            if (!bucket.hasPredictionData) {
                drawRect(Color(0xFFD5D9DE), Offset(left, size.height * 0.82f), androidx.compose.ui.geometry.Size(width, size.height * 0.18f))
            } else if (bucket.totalCount == 0) {
                drawRect(Color(0xFF246B60), Offset(left, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(width, 2.dp.toPx()))
            } else {
                val recommendHeight = size.height * bucket.recommendCount / maxCount.toFloat()
                val requiredHeight = size.height * bucket.requiredCount / maxCount.toFloat()
                drawRect(
                    Color(0xFF246B60),
                    Offset(left, size.height - recommendHeight),
                    androidx.compose.ui.geometry.Size(width, recommendHeight)
                )
                drawRect(
                    Color(0xFFC83F4E),
                    Offset(left, size.height - recommendHeight - requiredHeight),
                    androidx.compose.ui.geometry.Size(width, requiredHeight)
                )
            }
            if (index == selected) {
                drawRect(
                    Color(0xFF172026),
                    Offset(left, 0f),
                    androidx.compose.ui.geometry.Size(width, size.height),
                    style = Stroke(1.dp.toPx())
                )
            }
        }
    }
    DailyAxisLabels(buckets.map(DailyEventBucket::localDate))
    val bucket = buckets.getOrNull(selected) ?: return
    Text(
        if (!bucket.hasPredictionData) {
            "${bucket.localDate} · 예측 데이터 없음"
        } else {
            "${bucket.localDate} · 권장 ${bucket.recommendCount}건 · 필수 ${bucket.requiredCount}건 · 총 ${bucket.totalCount}건"
        }
    )
}

@Composable
private fun AuqBarChart(buckets: List<AuqBucket>, range: AuqRange) {
    var selected by remember(buckets) { mutableIntStateOf(buckets.lastIndex.coerceAtLeast(0)) }
    SelectableNormalizedBars(
        values = buckets.map(AuqBucket::averageScore),
        selected = selected,
        onSelect = { selected = it },
        accessibilityLabel = "AUQ 평균 막대 그래프",
        maximum = 48f,
        axisLabel = "세로축 0–48점 · 회색은 데이터 없음"
    )
    if (range == AuqRange.TODAY) FixedHourLabels()
    else DailyAxisLabels(buckets.map(AuqBucket::localLabel))
    val bucket = buckets.getOrNull(selected) ?: return
    val period = if (range == AuqRange.TODAY) {
        "${"%02d".format(selected)}:00–${"%02d".format(selected)}:59"
    } else {
        bucket.localLabel
    }
    Text(
        if (bucket.sampleCount == 0) {
            "$period · 데이터 없음"
        } else {
            "$period · 총점 ${"%.1f".format(bucket.averageScore ?: 0f)}/48 · " +
                "응답 ${bucket.sampleCount}회"
        }
    )
    Text(
        "점수가 높을수록 당시 음주 욕구 관련 응답이 높았습니다.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SelectableNormalizedBars(
    values: List<Float?>,
    selected: Int,
    onSelect: (Int) -> Unit,
    accessibilityLabel: String,
    maximum: Float = 1f,
    axisLabel: String = "갈망 가능성 구간 · 회색은 데이터 없음"
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .semantics { contentDescription = accessibilityLabel }
            .pointerInput(values) {
                detectTapGestures { point ->
                    onSelect(barIndex(point.x, size.width.toFloat(), values.size))
                }
            }
    ) {
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        val gap = minOf(slot * 0.25f, 5.dp.toPx())
        values.forEachIndexed { index, value ->
            val left = index * slot + gap / 2f
            val width = (slot - gap).coerceAtLeast(1f)
            val normalized = value?.div(maximum)?.coerceIn(0f, 1f)
            if (normalized == null) {
                drawRect(
                    Color(0xFFD5D9DE),
                    Offset(left, size.height * 0.82f),
                    androidx.compose.ui.geometry.Size(width, size.height * 0.18f)
                )
            } else {
                val height = (size.height * normalized).coerceAtLeast(2.dp.toPx())
                drawRect(
                    Color(0xFF246B60),
                    Offset(left, size.height - height),
                    androidx.compose.ui.geometry.Size(width, height)
                )
            }
            if (index == selected) {
                drawRect(
                    Color(0xFF172026),
                    Offset(left, 0f),
                    androidx.compose.ui.geometry.Size(width, size.height),
                    style = Stroke(1.dp.toPx())
                )
            }
        }
    }
    Text(axisLabel, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun FixedHourLabels() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("00시", "06시", "12시", "18시", "23시").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun DailyAxisLabels(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(labels.first(), style = MaterialTheme.typography.labelSmall)
        if (labels.size > 2) Text(labels[labels.size / 2], style = MaterialTheme.typography.labelSmall)
        if (labels.size > 1) Text(labels.last(), style = MaterialTheme.typography.labelSmall)
    }
}

private fun barIndex(x: Float, width: Float, count: Int): Int {
    if (count <= 1 || width <= 0f) return 0
    return ((x / width) * count).toInt().coerceIn(0, count - 1)
}

internal fun normalizedAuqToRaw(value: Float): Float = value.coerceIn(0f, 1f) * 48f

@Composable
private fun CravingProbabilityLineChart(series: CravingProbabilitySeries) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics { contentDescription = "최근 1시간 갈망 가능성 0에서 100퍼센트 선 그래프" }
    ) {
        val duration = (series.toMs - series.fromMs).coerceAtLeast(1L)
        CravingProbabilitySeriesState.segments(series).forEach { segment ->
            if (segment.size == 1) {
                val point = segment.first()
                val x = ((point.atMs - series.fromMs).toFloat() / duration.toFloat()) * size.width
                val y = size.height * (1f - point.probability)
                drawCircle(Color(0xFF246B60), radius = 3.dp.toPx(), center = Offset(x, y))
            } else {
                val path = Path()
                segment.forEachIndexed { index, point ->
                    val x = ((point.atMs - series.fromMs).toFloat() / duration.toFloat()) * size.width
                    val y = size.height * (1f - point.probability)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFF246B60), style = Stroke(width = 3.dp.toPx()))
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("60분 전", style = MaterialTheme.typography.labelSmall)
        Text("지금", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SignalLineChart(values: List<Float>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(100.dp)) {
        if (values.size < 2) return@Canvas
        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val point = Offset(index * step, size.height - ((value - min) / span) * size.height)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, Color(0xFF486A8C), style = Stroke(width = 3f))
    }
}

@Composable
private fun StateSummaryCard(title: String, value: DashboardStateSummary?) = DashboardCard(title) {
    if (value == null) {
        Text("상태 추론 기록이 없습니다")
    } else {
        Text("상태: ${stateLabel(value.state)} · ${localTime(value.createdAtMs)}")
        when (value.summaryStatus) {
            "ready" -> Text(value.summary ?: "요약을 사용할 수 없습니다")
            "unavailable" -> Text("상태 요약을 사용할 수 없습니다")
            else -> Text("상태 요약을 준비 중입니다")
        }
    }
}

private fun stateLabel(value: String): String = when (value.lowercase()) {
    "low" -> "Low"
    "mid" -> "Mid"
    "high" -> "High"
    else -> "알 수 없음"
}

private fun reportStatusLabel(value: String): String = when (value.lowercase()) {
    "generating" -> "생성 중"
    "ready" -> "준비됨"
    "failed" -> "실패"
    else -> "생성 전"
}

private fun localTime(epochMs: Long): String = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMs))
