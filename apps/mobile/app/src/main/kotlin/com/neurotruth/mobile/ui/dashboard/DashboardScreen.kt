package com.neurotruth.mobile.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.Auq
import com.neurotruth.mobile.core.CravingStage
import com.neurotruth.mobile.core.WatchConnectionState
import com.neurotruth.mobile.data.AuqBucket
import com.neurotruth.mobile.data.CravingSeries
import com.neurotruth.mobile.data.DailyEventBucket
import com.neurotruth.mobile.data.DashboardRepository
import com.neurotruth.mobile.data.HourlyCravingBucket
import com.neurotruth.mobile.data.LivePpgState
import com.neurotruth.mobile.data.LivePpgWindow
import com.neurotruth.mobile.data.PpgPreviewParser
import com.neurotruth.mobile.data.PpgPreviewResult
import com.neurotruth.mobile.data.PpgSample
import com.neurotruth.mobile.ui.theme.CardTone
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
import com.neurotruth.mobile.ui.theme.ScreenTitle
import com.neurotruth.mobile.ui.theme.SectionCard
import com.neurotruth.mobile.ui.theme.SectionLabel
import com.neurotruth.mobile.ui.theme.SecondaryButton
import com.neurotruth.mobile.ui.theme.cravingAccent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val ChartHeight = 168.dp
private val AxisLabelWidth = 44.dp

/**
 * NT-08 · 대시보드.
 *
 * One scroll, five sections: 최근 1시간, 시간대별 갈망 가능성, 갈망 이벤트, 자가설문, 신호 데이터(PPG).
 *
 * The PPG section carries both sources the PRD names — the live Watch trace and the stored preview
 * of a selected prediction — under separate headings. Home stays waveform-free by design.
 *
 * Two absences are never drawn as zero. An hour with no prediction is a grey empty bar rather than a
 * 0% stack, and a day with no prediction is an empty span rather than the dot that marks a day whose
 * predictions raised no alert. Charts are drawn with [Canvas]; no charting library is involved.
 *
 * The patient dashboard carries no state-inference card and no report-status card by design.
 */
@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModel.factory(NeuroTruthApp.from(LocalContext.current)),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        viewModel.onScreenActive(true)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onScreenActive(true)
                Lifecycle.Event.ON_STOP -> viewModel.onScreenActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenActive(false)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NeuroTruthSpacing.screenHorizontal,
                vertical = NeuroTruthSpacing.screenVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenCards),
    ) {
        ScreenTitle("대시보드")

        // if/else, never an early return@Column: bailing out of a layout content lambda after
        // emitting composables corrupts the slot table and crashes the next recomposition.
        if (state.isLoading && state.dashboard == null && state.series == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(32.dp)
                        .semantics { contentDescription = "기록을 불러오고 있어요" },
                )
            }
        } else {
            RecentHourSection(
                series = state.series,
                errorMessage = state.seriesError,
                selectedPredictionId = state.selectedPredictionId,
                onSelectPrediction = viewModel::onPredictionSelected,
            )

            HourlyStackSection(
                buckets = state.dashboard?.hourly.orEmpty(),
                errorMessage = state.dashboardError,
                selectedHour = state.selectedHour,
                onSelect = viewModel::onHourSelected,
            )

            EventSection(
                buckets = state.dashboard?.events.orEmpty(),
                range = state.eventRange,
                errorMessage = state.dashboardError,
                selectedIndex = state.selectedEventIndex,
                onRangeChange = viewModel::onEventRangeChanged,
                onSelect = viewModel::onEventSelected,
            )

            AuqSection(
                buckets = state.dashboard?.auq.orEmpty(),
                range = state.auqRange,
                bucketUnit = state.dashboard?.auqBucketUnit ?: "day",
                errorMessage = state.dashboardError,
                selectedIndex = state.selectedAuqIndex,
                onRangeChange = viewModel::onAuqRangeChanged,
                onSelect = viewModel::onAuqSelected,
            )

            PpgSection(
                live = state.livePpg,
                watchState = state.watchState,
                hasSelectionPath = state.hasPpgSelectionPath,
                selectedPredictionId = state.selectedPredictionId,
                isLoading = state.isPpgLoading,
                result = state.ppg,
                errorMessage = state.ppgError,
            )

            if (state.hasAnyError) {
                SecondaryButton(
                    text = "다시 시도",
                    onClick = viewModel::refresh,
                    contentDescription = "대시보드 다시 불러오기",
                )
            }

            Text(
                text = "연구용 모델 출력이며 진단이나 임상적 갈망 강도를 의미하지 않습니다. 기기 현지시간 기준.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 1 · 최근 1시간
// ---------------------------------------------------------------------------------------------

/**
 * `range=1h` only. A truncated 24-hour series has a different bucket width and a different end
 * point, so it is never substituted here.
 */
@Composable
private fun RecentHourSection(
    series: CravingSeries?,
    errorMessage: String?,
    selectedPredictionId: String?,
    onSelectPrediction: (String?) -> Unit,
) {
    SectionBlock(label = "최근 1시간 변화") {
        when {
            errorMessage != null -> EmptyLine(errorMessage)

            series == null || !series.hasData -> {
                EmptyLine("최근 1시간 측정 기록이 없어요.")
                LineChartFrame(series = null, selectedPredictionId = null, onSelect = {})
            }

            else -> {
                val latest = series.latest
                val stage = latest?.let { CravingStage.of(it.probability) }
                Text(
                    text = buildString {
                        append("최신 값 ")
                        append(percentOf(latest?.probability))
                        if (stage != null) append(" · ${stage.label}")
                        latest?.let { append(" · ${clockOf(it.atMs)}") }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "최신 갈망 가능성 ${percentOf(latest?.probability)}" +
                            (stage?.let { ", ${it.label}" } ?: "")
                    },
                )
                LineChartFrame(
                    series = series,
                    selectedPredictionId = selectedPredictionId,
                    onSelect = onSelectPrediction,
                )
                Text(
                    text = if (series.hasSelectablePoints) {
                        "10초 간격 · 측정이 없는 구간은 선을 잇지 않아요. 점을 누르면 그 측정의 신호를 볼 수 있어요."
                    } else {
                        "10초 간격 · 측정이 없는 구간은 선을 잇지 않아요."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LineChartFrame(
    series: CravingSeries?,
    selectedPredictionId: String?,
    onSelect: (String?) -> Unit,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val line = MaterialTheme.colorScheme.primary
    val selection = MaterialTheme.colorScheme.onSurface
    val safe = cravingAccent(CravingStage.SAFE)
    val observe = cravingAccent(CravingStage.OBSERVE)
    val caution = cravingAccent(CravingStage.CAUTION)
    val severe = cravingAccent(CravingStage.SEVERE)
    val accents = mapOf(
        CravingStage.SAFE to safe,
        CravingStage.OBSERVE to observe,
        CravingStage.CAUTION to caution,
        CravingStage.SEVERE to severe,
    )

    Row(modifier = Modifier.fillMaxWidth()) {
        AxisLabels(listOf("100%", "75%", "50%", "25%", "0%"))
        Column(modifier = Modifier.fillMaxWidth()) {
            val selectable = series?.selectablePoints.orEmpty()
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight)
                    .semantics {
                        contentDescription = when {
                            series == null || !series.hasData ->
                                "최근 1시간 갈망 가능성 그래프, 데이터 없음"

                            selectable.isNotEmpty() ->
                                "최근 1시간 갈망 가능성 그래프, ${series.points.size}개 측정, " +
                                    "점을 눌러 신호를 볼 측정을 선택"

                            else -> "최근 1시간 갈망 가능성 그래프, ${series.points.size}개 측정"
                        }
                    }
                    .pointerInput(selectable.size, series?.fromMs) {
                        if (selectable.isEmpty() || series == null) return@pointerInput
                        detectTapGestures { offset ->
                            // Nearest selectable sample on the time axis; no id is ever invented.
                            val span = (series.toMs - series.fromMs).coerceAtLeast(1L).toFloat()
                            val nearest = selectable.minByOrNull { point ->
                                val x = ((point.atMs - series.fromMs) / span) * size.width
                                kotlin.math.abs(x - offset.x)
                            }
                            onSelect(nearest?.predictionId)
                        }
                    },
            ) {
                for (step in 0..4) {
                    val y = size.height * step / 4f
                    drawLine(
                        color = grid,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                }
                if (series == null || !series.hasData) return@Canvas

                val span = (series.toMs - series.fromMs).coerceAtLeast(1L).toFloat()
                fun xOf(atMs: Long): Float =
                    (((atMs - series.fromMs).toFloat() / span).coerceIn(0f, 1f)) * size.width

                fun yOf(probability: Float): Float = size.height * (1f - probability.coerceIn(0f, 1f))

                for (segment in series.segments()) {
                    if (segment.size == 1) {
                        val point = segment.first()
                        drawCircle(
                            color = accents[CravingStage.of(point.probability)] ?: line,
                            radius = 3.dp.toPx(),
                            center = Offset(xOf(point.atMs), yOf(point.probability)),
                        )
                        continue
                    }
                    val path = Path()
                    segment.forEachIndexed { index, point ->
                        val x = xOf(point.atMs)
                        val y = yOf(point.probability)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path = path, color = line, style = Stroke(width = 2.dp.toPx()))
                }

                // The end point is the latest real sample, at its own timestamp.
                series.latest?.let { point ->
                    drawCircle(
                        color = accents[CravingStage.of(point.probability)] ?: line,
                        radius = 4.dp.toPx(),
                        center = Offset(xOf(point.atMs), yOf(point.probability)),
                    )
                }

                selectable.firstOrNull { it.predictionId == selectedPredictionId }?.let { point ->
                    drawCircle(
                        color = selection,
                        radius = 7.dp.toPx(),
                        center = Offset(xOf(point.atMs), yOf(point.probability)),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            AxisRow(listOf("-60분", "-45", "-30", "-15", "현재"))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 2 · 시간대별 갈망 가능성
// ---------------------------------------------------------------------------------------------

@Composable
private fun HourlyStackSection(
    buckets: List<HourlyCravingBucket>,
    errorMessage: String?,
    selectedHour: Int?,
    onSelect: (Int?) -> Unit,
) {
    SectionBlock(label = "시간대별 갈망 가능성") {
        // if/else, never an early return@SectionBlock: bailing out of a layout content lambda after
        // emitting composables corrupts the slot table and crashes the next recomposition.
        when {
            errorMessage != null -> EmptyLine(errorMessage)

            buckets.isEmpty() -> EmptyLine("오늘 측정 기록이 없어요.")

            else -> {
                if (buckets.none { it.hasData }) {
                    EmptyLine("오늘은 아직 측정 기록이 없어요.")
                }

                StackedHourChart(buckets = buckets, selectedHour = selectedHour, onSelect = onSelect)
                StageLegend()

                val selected = selectedHour?.let { hour -> buckets.firstOrNull { it.hour == hour } }
                if (selected == null) {
                    Text(
                        text = "막대를 누르면 시간대별 자세한 값을 볼 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    HourDetailCard(selected)
                }
            }
        }
    }
}

@Composable
private fun StackedHourChart(
    buckets: List<HourlyCravingBucket>,
    selectedHour: Int?,
    onSelect: (Int?) -> Unit,
) {
    val empty = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val selection = MaterialTheme.colorScheme.onSurface
    val stageColors = CravingStage.entries.associateWith { cravingAccent(it) }

    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(AxisLabelWidth))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight)
                .semantics {
                    contentDescription = "오늘 24시간 갈망 구성 막대그래프, 막대를 눌러 시간대를 선택"
                }
                .pointerInput(buckets.size) {
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / buckets.size.coerceAtLeast(1)
                        val index = (offset.x / slot).toInt().coerceIn(0, buckets.lastIndex)
                        onSelect(buckets[index].hour)
                    }
                },
        ) {
            val slot = size.width / buckets.size
            val barWidth = slot * 0.72f
            val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())

            buckets.forEachIndexed { index, bucket ->
                val left = index * slot + (slot - barWidth) / 2f

                if (!bucket.hasData) {
                    // Zero samples is not 0% — it is a grey placeholder with no composition.
                    drawRoundRect(
                        color = empty,
                        topLeft = Offset(left, size.height * 0.72f),
                        size = Size(barWidth, size.height * 0.28f),
                        cornerRadius = corner,
                    )
                } else {
                    var bottom = size.height
                    for (stage in CravingStage.entries) {
                        val portion = bucket.proportionOf(stage)
                        if (portion <= 0f) continue
                        val height = size.height * portion
                        drawRect(
                            color = stageColors[stage] ?: empty,
                            topLeft = Offset(left, bottom - height),
                            size = Size(barWidth, height),
                        )
                        bottom -= height
                    }
                }

                if (bucket.hour == selectedHour) {
                    drawRoundRect(
                        color = selection,
                        topLeft = Offset(left - 2f, 0f),
                        size = Size(barWidth + 4f, size.height),
                        cornerRadius = corner,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(AxisLabelWidth))
        AxisRow(listOf("00시", "06시", "12시", "18시", "23시"))
    }
}

@Composable
private fun StageLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CravingStage.entries.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
            ) {
                pair.forEach { stage ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(cravingAccent(stage)),
                        )
                        Text(text = stage.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun HourDetailCard(bucket: HourlyCravingBucket) {
    SectionCard(tone = CardTone.Low, contentGap = 6.dp) {
        Text(text = bucket.hourLabel, style = MaterialTheme.typography.titleMedium)
        if (!bucket.hasData) {
            Text(
                text = CravingStage.NO_DATA_LABEL,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics {
                    contentDescription = "${bucket.hourLabel}, ${CravingStage.NO_DATA_LABEL}"
                },
            )
        } else {
            Text(
                text = "측정 ${bucket.sampleCount}회",
                style = MaterialTheme.typography.bodyMedium,
            )
            bucket.proportions().forEach { (stage, portion) ->
                Text(
                    text = "${stage.label} ${percentOf(portion)} (${bucket.stageCounts[stage] ?: 0}회)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cravingAccent(stage),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 3 · 갈망 이벤트
// ---------------------------------------------------------------------------------------------

@Composable
private fun EventSection(
    buckets: List<DailyEventBucket>,
    range: String,
    errorMessage: String?,
    selectedIndex: Int?,
    onRangeChange: (String) -> Unit,
    onSelect: (Int?) -> Unit,
) {
    SectionBlock(
        label = "갈망 이벤트",
        trailing = {
            RangeChips(
                options = listOf(
                    DashboardRepository.EVENT_RANGE_7D to "7일",
                    DashboardRepository.EVENT_RANGE_30D to "30일",
                ),
                selected = range,
                onSelect = onRangeChange,
            )
        },
    ) {
        // if/else, never an early return@SectionBlock: bailing out of a layout content lambda after
        // emitting composables corrupts the slot table and crashes the next recomposition.
        when {
            errorMessage != null -> EmptyLine(errorMessage)

            buckets.isEmpty() || buckets.none { it.hasPredictionData } ->
                EmptyLine("예측 기록이 없어 이벤트를 표시할 수 없어요.")

            else -> {
                EventChart(buckets = buckets, selectedIndex = selectedIndex, onSelect = onSelect)
                Text(
                    text = "점은 예측은 있었지만 알림이 없던 날, 빈 자리는 예측 자체가 없던 날이에요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectedIndex?.let { index ->
                    buckets.getOrNull(index)?.let { EventDetailCard(it) }
                }
            }
        }
    }
}

@Composable
private fun EventChart(
    buckets: List<DailyEventBucket>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val bar = MaterialTheme.colorScheme.tertiary
    val selection = MaterialTheme.colorScheme.onSurface
    val maxCount = buckets.maxOfOrNull { it.totalCount }?.coerceAtLeast(1) ?: 1

    Row(modifier = Modifier.fillMaxWidth()) {
        AxisLabels(listOf("$maxCount", "", "0"))
        Column(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight)
                    .semantics {
                        contentDescription = "일별 갈망 이벤트 막대그래프, 막대를 눌러 날짜를 선택"
                    }
                    .pointerInput(buckets.size) {
                        detectTapGestures { offset ->
                            val slot = size.width.toFloat() / buckets.size.coerceAtLeast(1)
                            val index = (offset.x / slot).toInt().coerceIn(0, buckets.lastIndex)
                            onSelect(index)
                        }
                    },
            ) {
                val baseline = size.height - 2.dp.toPx()
                drawLine(
                    color = grid,
                    start = Offset(0f, baseline),
                    end = Offset(size.width, baseline),
                    strokeWidth = 1f,
                )
                val slot = size.width / buckets.size
                val barWidth = slot * 0.6f
                val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())

                buckets.forEachIndexed { index, bucket ->
                    val left = index * slot + (slot - barWidth) / 2f
                    when {
                        // No prediction at all: an empty span. Nothing is drawn.
                        bucket.isNoData -> Unit

                        // Predictions ran and raised no alert: a real zero, drawn as a dot.
                        bucket.isValidZero -> drawCircle(
                            color = bar,
                            radius = 3.dp.toPx(),
                            center = Offset(left + barWidth / 2f, baseline - 3.dp.toPx()),
                        )

                        else -> {
                            val height =
                                (baseline - 4.dp.toPx()) * bucket.totalCount / maxCount.toFloat()
                            drawRoundRect(
                                color = bar,
                                topLeft = Offset(left, baseline - height),
                                size = Size(barWidth, height),
                                cornerRadius = corner,
                            )
                        }
                    }
                    if (index == selectedIndex) {
                        drawRoundRect(
                            color = selection,
                            topLeft = Offset(left - 2f, 0f),
                            size = Size(barWidth + 4f, baseline),
                            cornerRadius = corner,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            AxisRow(edgeLabels(buckets.map { shortLabel(it.localDate) }))
        }
    }
}

@Composable
private fun EventDetailCard(bucket: DailyEventBucket) {
    val summary = when {
        bucket.isNoData -> "예측 기록 없음"
        bucket.isValidZero -> "이벤트 0회 (예측은 있었어요)"
        else -> "이벤트 ${bucket.totalCount}회 · 권유 ${bucket.recommendCount}, 필요 ${bucket.requiredCount}"
    }
    SectionCard(tone = CardTone.Low, contentGap = 6.dp) {
        Text(text = longLabel(bucket.localDate), style = MaterialTheme.typography.titleMedium)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics {
                contentDescription = "${longLabel(bucket.localDate)}, $summary"
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 4 · 자가설문
// ---------------------------------------------------------------------------------------------

@Composable
private fun AuqSection(
    buckets: List<AuqBucket>,
    range: String,
    bucketUnit: String,
    errorMessage: String?,
    selectedIndex: Int?,
    onRangeChange: (String) -> Unit,
    onSelect: (Int?) -> Unit,
) {
    SectionBlock(
        label = "자가설문 평균",
        trailing = {
            RangeChips(
                options = listOf(
                    DashboardRepository.AUQ_RANGE_TODAY to "오늘",
                    DashboardRepository.AUQ_RANGE_7D to "7일",
                    DashboardRepository.AUQ_RANGE_30D to "30일",
                ),
                selected = range,
                onSelect = onRangeChange,
            )
        },
    ) {
        // if/else, never an early return@SectionBlock: bailing out of a layout content lambda after
        // emitting composables corrupts the slot table and crashes the next recomposition.
        when {
            errorMessage != null -> EmptyLine(errorMessage)

            buckets.none { it.hasData } -> EmptyLine("아직 응답한 자가설문이 없어요.")

            else -> {
                AuqChart(buckets = buckets, selectedIndex = selectedIndex, onSelect = onSelect)
                Text(
                    text = if (bucketUnit == "hour") "오늘 시간대별 평균 (0-48)" else "일별 평균 (0-48)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectedIndex?.let { index ->
                    buckets.getOrNull(index)?.let { AuqDetailCard(it, range) }
                }
            }
        }
    }
}

@Composable
private fun AuqChart(
    buckets: List<AuqBucket>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val bar = MaterialTheme.colorScheme.primary
    val selection = MaterialTheme.colorScheme.onSurface

    Row(modifier = Modifier.fillMaxWidth()) {
        AxisLabels(listOf("48", "24", "0"))
        Column(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight)
                    .semantics {
                        contentDescription = "자가설문 평균 막대그래프, 막대를 눌러 구간을 선택"
                    }
                    .pointerInput(buckets.size) {
                        detectTapGestures { offset ->
                            val slot = size.width.toFloat() / buckets.size.coerceAtLeast(1)
                            val index = (offset.x / slot).toInt().coerceIn(0, buckets.lastIndex)
                            onSelect(index)
                        }
                    },
            ) {
                val baseline = size.height - 2.dp.toPx()
                drawLine(
                    color = grid,
                    start = Offset(0f, baseline),
                    end = Offset(size.width, baseline),
                    strokeWidth = 1f,
                )
                val slot = size.width / buckets.size
                val barWidth = slot * 0.6f
                val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())

                buckets.forEachIndexed { index, bucket ->
                    val left = index * slot + (slot - barWidth) / 2f
                    val score = bucket.averageScore
                    when {
                        // No response is an empty span, never a zero average.
                        !bucket.hasData || score == null -> Unit

                        score <= 0f -> drawCircle(
                            color = bar,
                            radius = 3.dp.toPx(),
                            center = Offset(left + barWidth / 2f, baseline - 3.dp.toPx()),
                        )

                        else -> {
                            val height =
                                (baseline - 4.dp.toPx()) * score / Auq.SCALE_MAX.toFloat()
                            drawRoundRect(
                                color = bar,
                                topLeft = Offset(left, baseline - height),
                                size = Size(barWidth, height),
                                cornerRadius = corner,
                            )
                        }
                    }
                    if (index == selectedIndex) {
                        drawRoundRect(
                            color = selection,
                            topLeft = Offset(left - 2f, 0f),
                            size = Size(barWidth + 4f, baseline),
                            cornerRadius = corner,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            AxisRow(edgeLabels(buckets.map { it.label }))
        }
    }
}

@Composable
private fun AuqDetailCard(bucket: AuqBucket, range: String) {
    val period = when (range) {
        DashboardRepository.AUQ_RANGE_TODAY -> "오늘 ${bucket.label}"
        DashboardRepository.AUQ_RANGE_7D -> "최근 7일 · ${bucket.label}"
        else -> "최근 30일 · ${bucket.label}"
    }
    val summary = if (!bucket.hasData || bucket.averageScore == null) {
        "응답 없음"
    } else {
        "평균 ${bucket.averageScore.roundToInt()}/${Auq.SCALE_MAX} · 응답 ${bucket.sampleCount}회"
    }
    SectionCard(tone = CardTone.Low, contentGap = 6.dp) {
        Text(text = period, style = MaterialTheme.typography.titleMedium)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = "$period, $summary" },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 5 · 신호 데이터 (PPG)
// ---------------------------------------------------------------------------------------------

/**
 * Two sources, labelled apart so a live trace is never read as a stored measurement.
 *
 * 실시간 is the watch's PPG_GREEN stream, held only while it keeps arriving. 선택한 측정 is the stored
 * preview of one selected prediction, served `no-store` over a temporarily decrypted window and kept
 * in screen state only for as long as the selection lasts. Neither is ever drawn as a flat zero line.
 */
@Composable
private fun PpgSection(
    live: LivePpgState,
    watchState: WatchConnectionState,
    hasSelectionPath: Boolean,
    selectedPredictionId: String?,
    isLoading: Boolean,
    result: PpgPreviewResult?,
    errorMessage: String?,
) {
    SectionBlock(label = "신호 데이터") {
        Text(
            text = "실시간 · Watch",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        LivePpgBlock(live = live, watchState = watchState)

        HorizontalDivider()

        Text(
            text = "선택한 측정 · 저장된 기록",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        StoredPpgBlock(
            hasSelectionPath = hasSelectionPath,
            selectedPredictionId = selectedPredictionId,
            isLoading = isLoading,
            result = result,
            errorMessage = errorMessage,
        )
    }
}

/** Live only while samples keep arriving; a stalled buffer reverts to the waiting copy. */
@Composable
private fun LivePpgBlock(live: LivePpgState, watchState: WatchConnectionState) {
    when (live) {
        is LivePpgState.Streaming -> {
            PpgWaveform(
                samples = live.trace.samples,
                accent = MaterialTheme.colorScheme.primary,
                description = "실시간 PPG 신호 그래프, ${live.trace.samples.size}개 지점",
            )
            Text(
                text = "최근 ${LivePpgWindow.WINDOW_MS / 1000}초 · ${live.trace.samples.size}개 표시 지점 " +
                    "· ${clockOf(live.trace.latestAtMs)} 기준",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LivePpgState.Unavailable -> EmptyLine("Watch가 연결되어 있지 않아 실시간 신호를 볼 수 없어요.")

        LivePpgState.Waiting -> EmptyLine(
            when (watchState) {
                WatchConnectionState.CONNECTED -> "Watch에서 신호가 아직 도착하지 않았어요."
                WatchConnectionState.ERROR -> "Watch 연결 상태를 확인할 수 없어요."
                else -> "Watch 연결 상태를 확인하고 있어요."
            },
        )
    }
}

@Composable
private fun StoredPpgBlock(
    hasSelectionPath: Boolean,
    selectedPredictionId: String?,
    isLoading: Boolean,
    result: PpgPreviewResult?,
    errorMessage: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows)) {
        when {
            errorMessage != null -> EmptyLine(errorMessage)

            isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        .semantics { contentDescription = "신호 데이터를 불러오고 있어요" },
                )
            }

            // The recent-hour buckets are averages and carry no prediction id, so there is nothing
            // to select yet. No id is invented to fill the gap.
            !hasSelectionPath -> EmptyLine(
                "신호를 보려면 먼저 측정을 선택해야 해요. 지금은 최근 1시간 기록에서 선택할 수 있는 측정이 없어요.",
            )

            selectedPredictionId == null -> EmptyLine("위 그래프에서 측정을 선택하면 그 구간의 신호를 볼 수 있어요.")

            result is PpgPreviewResult.Empty || result == null ->
                EmptyLine("선택한 측정의 신호 데이터가 없어요.")

            result is PpgPreviewResult.Invalid -> EmptyLine(ppgInvalidCopy(result.reason))

            result is PpgPreviewResult.Ready -> {
                PpgWaveform(
                    samples = result.preview.samples,
                    accent = MaterialTheme.colorScheme.secondary,
                    description = "선택한 측정의 PPG 신호 그래프, ${result.preview.samples.size}개 지점",
                )
                Text(
                    text = buildString {
                        append("${result.preview.samples.size}개 표시 지점")
                        result.preview.samplingHz?.let { append(" · 약 ${it.roundToInt()}Hz") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Shared by both halves so a live trace and a stored preview are drawn the same way. */
@Composable
private fun PpgWaveform(samples: List<PpgSample>, accent: Color, description: String) {
    if (samples.isEmpty()) return
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val minimum = samples.minOf(PpgSample::value)
    val maximum = samples.maxOf(PpgSample::value)

    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(AxisLabelWidth))
        Column(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight)
                    .semantics { contentDescription = description },
            ) {
                drawLine(
                    color = grid,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1f,
                )
                val first = samples.first().atMs
                val span = (samples.last().atMs - first).coerceAtLeast(1L).toFloat()
                // A constant signal is centred rather than flattened onto the axis, which would be
                // indistinguishable from the absent-data case.
                val amplitude = (maximum - minimum).takeIf { it > 0f }
                val path = Path()
                samples.forEachIndexed { index, sample ->
                    val x = ((sample.atMs - first) / span) * size.width
                    val y = if (amplitude == null) {
                        size.height / 2f
                    } else {
                        size.height * (1f - (sample.value - minimum) / amplitude)
                    }
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = accent, style = Stroke(width = 1.5.dp.toPx()))
            }
            AxisRow(listOf(clockOf(samples.first().atMs), clockOf(samples.last().atMs)))
        }
    }
}

private fun ppgInvalidCopy(reason: String): String = when (reason) {
    PpgPreviewParser.REASON_TOO_MANY_POINTS ->
        "신호 데이터를 표시할 수 없어요. 응답 형식이 예상과 달라요."
    PpgPreviewParser.REASON_OUT_OF_ORDER ->
        "신호 데이터의 시간 순서가 맞지 않아 표시하지 않았어요."
    else -> "신호 데이터를 표시할 수 없어요."
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

/**
 * One dashboard section: a grouping [SectionLabel] (with optional trailing range chips) above a
 * shared tonal [SectionCard] that holds the chart, note, and detail cards.
 */
@Composable
private fun SectionBlock(
    label: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel(label)
            trailing?.invoke()
        }
        SectionCard(content = content)
    }
}

@Composable
private fun RangeChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.semantics {
                    contentDescription = "$label 범위" + if (value == selected) ", 선택됨" else ""
                },
            )
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = text },
    )
}

@Composable
private fun AxisLabels(labels: List<String>) {
    Column(
        modifier = Modifier
            .width(AxisLabelWidth)
            .height(ChartHeight),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

@Composable
private fun AxisRow(labels: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Keeps a 30-bar axis readable: only the ends and the middle carry a label. */
private fun edgeLabels(labels: List<String>): List<String> = when {
    labels.isEmpty() -> emptyList()
    labels.size <= 7 -> labels
    else -> listOf(labels.first(), labels[labels.size / 2], labels.last())
}

private fun percentOf(value: Float?): String =
    if (value == null) CravingStage.NO_DATA_LABEL else "${(value * 100f).roundToInt()}%"

private fun clockOf(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private fun shortLabel(localDate: String): String {
    val parts = localDate.split('-')
    return if (parts.size == 3) "${parts[1].trimStart('0')}/${parts[2]}" else localDate
}

private fun longLabel(localDate: String): String {
    val parts = localDate.split('-')
    return if (parts.size == 3) {
        "${parts[1].trimStart('0')}월 ${parts[2].trimStart('0')}일"
    } else {
        localDate
    }
}
