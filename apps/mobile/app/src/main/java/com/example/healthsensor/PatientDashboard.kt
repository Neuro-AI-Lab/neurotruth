package com.example.healthsensor

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class DashboardRange(val wire: String, val label: String) {
    HOURS_24("24h", "24시간"),
    DAYS_7("7d", "7일"),
    DAYS_30("30d", "30일")
}

data class DashboardPrediction(
    val id: String,
    val atMs: Long,
    val stateClass: String,
    val probability: Float?,
    val ppgPreviewAvailable: Boolean
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
                    scaleMax = item.floatOrNull("scaleMax") ?: 56f
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

    fun load(range: DashboardRange = _range.value) {
        if (!MobileAuthRuntime.state.value.authenticated || _loading.value) return
        _range.value = range
        _loading.value = true
        _status.value = "기록을 불러오는 중입니다"
        _preview.value = null
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

    fun loadPreview(predictionId: String) {
        _status.value = "10초 PPG를 불러오는 중입니다"
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.getPpgPreview(predictionId) } }
                .onSuccess {
                    _preview.value = it
                    _status.value = "10초 PPG 미리보기"
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
    val range by viewModel.range.collectAsState()
    val data by viewModel.data.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val status by viewModel.status.collectAsState()
    val loading by viewModel.loading.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF7F8FA)).verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("내 상태 기록", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onClose) { Text("홈") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DashboardRange.values().forEach { candidate ->
                if (candidate == range) Button(onClick = { viewModel.load(candidate) }) { Text(candidate.label) }
                else OutlinedButton(onClick = { viewModel.load(candidate) }) { Text(candidate.label) }
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (loading && data == null) Text("불러오는 중…")

        DashboardCard("워치 실시간 PPG (휴대폰 내 표시)") {
            if (livePpg.isEmpty()) Text("워치 PPG를 기다리고 있습니다")
            else SignalLineChart(livePpg.takeLast(250).map { it.value })
        }

        DashboardCard("갈망 가능성 추세") {
            val predictions = data?.predictions.orEmpty()
            Text("Low · Mid · High 단계", style = MaterialTheme.typography.bodySmall)
            if (predictions.isEmpty()) Text("이 기간에는 예측 기록이 없습니다")
            else StateStepChart(predictions)
            predictions.lastOrNull { it.ppgPreviewAvailable }?.let { prediction ->
                OutlinedButton(onClick = { viewModel.loadPreview(prediction.id) }) { Text("10초 PPG 보기") }
            }
            preview?.let { value ->
                if (value.samples.isEmpty()) Text("PPG 미리보기를 사용할 수 없습니다")
                else SignalLineChart(value.samples.map { it.value })
            }
        }

        DashboardCard("AUQ 기록") {
            val assessments = data?.assessments.orEmpty()
            if (assessments.isEmpty()) Text("이 기간에는 AUQ 기록이 없습니다")
            else AuqChart(assessments)
        }

        DashboardCard("이벤트") {
            val events = data?.events.orEmpty()
            if (events.none { it.type == "session_started" }) Text("이 기간에는 대화 세션 기록이 없습니다")
            events.takeLast(12).forEach { event -> Text("${localTime(event.atMs)} · ${event.label}") }
        }

        StateSummaryCard("최근 실시간 상태", data?.latestState)
        StateSummaryCard("최근 장기 상태", data?.longitudinalState)

        DashboardCard("보고서 상태") {
            val reports = data?.reports.orEmpty()
            if (reports.isEmpty()) Text("생성된 보고서 상태가 없습니다")
            reports.forEach { report -> Text("${reportStatusLabel(report.status)} · ${localTime(report.updatedAtMs)}") }
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
private fun StateStepChart(values: List<DashboardPrediction>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
        if (values.isEmpty()) return@Canvas
        fun y(value: String): Float = when (value.lowercase()) {
            "high" -> size.height * .18f
            "mid" -> size.height * .5f
            else -> size.height * .82f
        }
        val step = if (values.size <= 1) 0f else size.width / (values.size - 1)
        val path = Path().apply { moveTo(0f, y(values.first().stateClass)) }
        values.drop(1).forEachIndexed { index, value ->
            val x = (index + 1) * step
            path.lineTo(x, y(values[index].stateClass))
            path.lineTo(x, y(value.stateClass))
        }
        drawPath(path, Color(0xFF246B60), style = Stroke(width = 5f))
    }
}

@Composable
private fun AuqChart(values: List<DashboardAssessment>) {
    val normalized = values.map { value ->
        ((value.rawScore - value.scaleMin) / (value.scaleMax - value.scaleMin).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }
    SignalLineChart(normalized)
    Text("AUQ 원점수: ${values.last().rawScore} / ${values.last().scaleMax}")
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
