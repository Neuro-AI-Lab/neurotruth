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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.neurotruth.mobile.data.CravingCalendarBucket
import com.neurotruth.mobile.data.CravingSeries
import com.neurotruth.mobile.data.CravingSeriesPoint
import com.neurotruth.mobile.data.DailyEventBucket
import com.neurotruth.mobile.data.DashboardRepository
import com.neurotruth.mobile.data.HourlyCravingBucket
import com.neurotruth.mobile.data.LivePpgState
import com.neurotruth.mobile.data.LivePpgWindow
import com.neurotruth.mobile.data.PpgSample
import com.neurotruth.mobile.ui.theme.CardTone
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
import com.neurotruth.mobile.ui.theme.ScreenTitle
import com.neurotruth.mobile.ui.theme.SectionCard
import com.neurotruth.mobile.ui.theme.SectionLabel
import com.neurotruth.mobile.ui.theme.SecondaryButton
import com.neurotruth.mobile.ui.theme.cravingAccent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val ChartHeight = 168.dp
private val EventAuqChartHeight = 240.dp
private val RecentHourChartHeight = 240.dp
private val SignalChartHeight = 112.dp
private val AxisLabelWidth = 44.dp
private const val HourlyStageSampleMaximum = 360
private const val DailyStageSampleMaximum = HourlyStageSampleMaximum * 24

/**
 * NT-08 · 대시보드.
 *
 * One scroll, five sections: 최근 1시간, 기간별 갈망 단계, 갈망 이벤트, 자기설문,
 * 실시간 신호(PPG/EDA).
 *
 * Missing measurements are left blank on the white chart surface rather than rendered as zero or
 * as a placeholder bar. A day with predictions but no alert remains a real zero and is drawn as a
 * dot. Charts are drawn with [Canvas]; no charting library is involved.
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
                selectedAtMs = state.selectedSeriesAtMs,
                onSelectPoint = viewModel::onSeriesPointSelected,
            )

            CalendarControls(
                view = state.calendarView,
                anchor = state.calendarAnchor,
                onViewChange = viewModel::onCalendarViewChanged,
                onPrevious = viewModel::onCalendarPrevious,
                onNext = viewModel::onCalendarNext,
                onAnchorChange = viewModel::onCalendarAnchorChanged,
            )
            CalendarSummarySections(
                buckets = state.calendar?.buckets.orEmpty(),
                view = state.calendarView,
                errorMessage = state.calendarError,
                selectedIndex = state.selectedCalendarIndex,
                onSelect = viewModel::onCalendarBucketSelected,
            )

            PpgSection(
                live = state.livePpg,
                watchState = state.watchState,
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
    selectedAtMs: Long?,
    onSelectPoint: (CravingSeriesPoint?) -> Unit,
) {
    SectionBlock(label = "최근 1시간 변화") {
        when {
            errorMessage != null -> EmptyLine(errorMessage)

            series == null || !series.hasData -> {
                EmptyLine("최근 1시간 측정 기록이 없어요.")
                StageTimelineFrame(series = null, selectedAtMs = null, onSelect = {})
            }

            else -> {
                val latest = series.latest
                val stage = latest?.let { CravingStage.of(it.probability) }
                Text(
                    text = buildString {
                        append("최신 단계 ")
                        append(stage?.label ?: CravingStage.NO_DATA_LABEL)
                        latest?.let { append(" · ${clockOf(it.atMs)}") }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "최신 갈망 단계 ${stage?.label ?: CravingStage.NO_DATA_LABEL}"
                    },
                )
                StageTimelineFrame(
                    series = series,
                    selectedAtMs = selectedAtMs,
                    onSelect = onSelectPoint,
                )
                selectedAtMs?.let { atMs ->
                    series.points.firstOrNull { it.atMs == atMs }?.let { point ->
                        Text(
                            text = "${clockOf(point.atMs)} · ${point.stage.label}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    text = "10초 간격 · 측정이 없는 구간은 연결하지 않아요. 구간을 누르면 시각과 단계를 확인할 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StageTimelineFrame(
    series: CravingSeries?,
    selectedAtMs: Long?,
    onSelect: (CravingSeriesPoint?) -> Unit,
) {
    val selection = MaterialTheme.colorScheme.onSurface
    val selectionHalo = MaterialTheme.colorScheme.surface
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
        AxisLabels(listOf("위험", "주의", "관찰", "안정"), RecentHourChartHeight)
        Column(modifier = Modifier.fillMaxWidth()) {
            val points = series?.points.orEmpty()
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RecentHourChartHeight)
                    .semantics {
                        contentDescription = when {
                            series == null || !series.hasData ->
                                "최근 1시간 갈망 단계 그래프, 데이터 없음"
                            else -> "최근 1시간 갈망 단계 그래프, ${series.points.size}개 측정"
                        }
                    }
                    .pointerInput(points.size, series?.fromMs) {
                        if (points.isEmpty() || series == null) return@pointerInput
                        detectTapGestures { offset ->
                            val span = (series.toMs - series.fromMs).coerceAtLeast(1L).toFloat()
                            val plotPadding = 8.dp.toPx()
                            val plotWidth = (size.width - (plotPadding * 2f)).coerceAtLeast(0f)
                            val nearest = points.minByOrNull { point ->
                                val x = plotPadding +
                                    (((point.atMs - series.fromMs) / span).coerceIn(0f, 1f) * plotWidth)
                                kotlin.math.abs(x - offset.x)
                            }
                            onSelect(nearest)
                        }
                    },
            ) {
                val plotPadding = 8.dp.toPx().coerceAtMost(size.height / 2f)
                val plotHeight = (size.height - (plotPadding * 2f)).coerceAtLeast(0f)
                val plotWidth = (size.width - (plotPadding * 2f)).coerceAtLeast(0f)
                fun yForLane(lane: Int): Float =
                    plotPadding + (plotHeight * lane / 3f)

                if (series == null || !series.hasData) return@Canvas

                val span = (series.toMs - series.fromMs).coerceAtLeast(1L).toFloat()
                fun xOf(atMs: Long): Float =
                    plotPadding +
                        (((atMs - series.fromMs).toFloat() / span).coerceIn(0f, 1f) * plotWidth)

                fun yOf(stage: CravingStage): Float {
                    val lane = when (stage) {
                        CravingStage.SEVERE -> 0
                        CravingStage.CAUTION -> 1
                        CravingStage.OBSERVE -> 2
                        CravingStage.SAFE -> 3
                    }
                    return yForLane(lane)
                }

                fun drawStageRun(run: List<CravingSeriesPoint>) {
                    if (run.isEmpty()) return
                    val bucketMs = series.bucketSeconds * 1_000L
                    val left = xOf(run.first().atMs)
                    val naturalRight = xOf(
                        (run.last().atMs + bucketMs).coerceAtMost(series.toMs),
                    )
                    val right = maxOf(naturalRight, left + 5.dp.toPx())
                        .coerceAtMost(size.width - plotPadding)
                    if (right <= left) return
                    val stage = run.first().stage
                    val capsuleHeight = 18.dp.toPx()
                    drawRoundRect(
                        color = accents[stage] ?: selection,
                        topLeft = Offset(left, yOf(stage) - capsuleHeight / 2f),
                        size = Size(right - left, capsuleHeight),
                        cornerRadius = CornerRadius(capsuleHeight / 2f, capsuleHeight / 2f),
                    )
                }

                for (segment in series.segments()) {
                    var runStart = 0
                    for (index in 1..segment.size) {
                        val runEnded =
                            index == segment.size || segment[index].stage != segment[index - 1].stage
                        if (runEnded) {
                            drawStageRun(segment.subList(runStart, index))
                            runStart = index
                        }
                    }
                }

                series.points.firstOrNull { it.atMs == selectedAtMs }?.let { point ->
                    val center = Offset(xOf(point.atMs), yOf(point.stage))
                    drawCircle(
                        color = selectionHalo,
                        radius = 7.dp.toPx(),
                        center = center,
                    )
                    drawCircle(
                        color = accents[point.stage] ?: selection,
                        radius = 4.5.dp.toPx(),
                        center = center,
                    )
                    drawCircle(
                        color = selection,
                        radius = 7.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            AxisRow(listOf("-60분", "-45", "-30", "-15", "현재"))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 2 · 선택한 일/월의 갈망 단계·이벤트·자기설문
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarControls(
    view: String,
    anchor: LocalDate,
    onViewChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAnchorChange: (LocalDate) -> Unit,
) {
    var pickerVisible by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val weekStart = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
    val weekEnd = weekStart.plusDays(6)
    SectionBlock(
        label = "기간 선택",
        trailing = {
            RangeChips(
                options = listOf(
                    DashboardRepository.CALENDAR_VIEW_DAY to "일간",
                    DashboardRepository.CALENDAR_VIEW_WEEK to "주간",
                    DashboardRepository.CALENDAR_VIEW_MONTH to "월간",
                ),
                selected = view,
                onSelect = onViewChange,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevious) { Text("이전") }
            TextButton(
                onClick = { pickerVisible = true },
                modifier = Modifier.semantics {
                    contentDescription = when (view) {
                        DashboardRepository.CALENDAR_VIEW_DAY -> "${anchor} 날짜 선택"
                        DashboardRepository.CALENDAR_VIEW_WEEK ->
                            "${weekStart}부터 ${weekEnd}까지 주 선택"
                        else -> "${anchor.year}년 ${anchor.monthValue}월 선택"
                    }
                },
            ) {
                Text(
                    when (view) {
                        DashboardRepository.CALENDAR_VIEW_DAY ->
                            "${anchor.monthValue}월 ${anchor.dayOfMonth}일"
                        DashboardRepository.CALENDAR_VIEW_WEEK ->
                            "${weekStart.monthValue}월 ${weekStart.dayOfMonth}일 - " +
                                "${weekEnd.monthValue}월 ${weekEnd.dayOfMonth}일"
                        else -> "${anchor.year}년 ${anchor.monthValue}월"
                    },
                )
            }
            TextButton(
                onClick = onNext,
                enabled = nextCalendarAnchor(view, anchor, today) != null,
            ) { Text("다음") }
        }
    }
    if (pickerVisible) {
        val latestSelectableDateMillis =
            today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val initialDate = minOf(anchor, today)
        val selectableDates = remember(latestSelectableDateMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                    return isCalendarAnchorSelectable(date, today)
                }

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            }
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis =
                initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = selectableDates,
        )
        DatePickerDialog(
            onDismissRequest = { pickerVisible = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val selected = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            if (isCalendarAnchorSelectable(selected, today)) {
                                onAnchorChange(selected)
                            }
                        }
                        pickerVisible = false
                    },
                ) { Text("선택") }
            },
            dismissButton = {
                TextButton(onClick = { pickerVisible = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun CalendarSummarySections(
    buckets: List<CravingCalendarBucket>,
    view: String,
    errorMessage: String?,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
) {
    CalendarStageSection(buckets, view, errorMessage, selectedIndex, onSelect)
    CalendarEventSection(buckets, view, errorMessage, selectedIndex, onSelect)
    CalendarAuqSection(buckets, view, errorMessage, selectedIndex, onSelect)
}

@Composable
private fun CalendarStageSection(
    buckets: List<CravingCalendarBucket>,
    view: String,
    errorMessage: String?,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
) {
    SectionBlock(label = if (view == DashboardRepository.CALENDAR_VIEW_DAY) "시간대별 갈망 단계" else "일별 갈망 단계") {
        when {
            errorMessage != null -> EmptyLine(errorMessage)
            buckets.none { it.hasStageData } -> EmptyLine("선택한 기간에 측정 기록이 없어요.")
            else -> {
                CalendarStageChart(buckets, view, selectedIndex, onSelect)
                StageLegend()
                selectedIndex?.let { index ->
                    buckets.getOrNull(index)?.let { bucket ->
                        val dominant = bucket.stageCounts.maxByOrNull { it.value }
                            ?.takeIf { it.value > 0 }?.key
                        val maximum = stageSampleMaximum(view)
                        SectionCard(tone = CardTone.Low, contentGap = 6.dp) {
                            Text(bucket.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (dominant == null) {
                                    "측정 데이터 없음"
                                } else {
                                    "총 ${bucket.countedSamples}회 / 최대 ${formatCount(maximum)}회" +
                                        " · 가장 많이 측정된 단계 ${dominant.label}"
                                },
                            )
                            if (dominant != null) {
                                CravingStage.entries.forEach { stage ->
                                    Text(
                                        text = "${stage.label} ${bucket.stageCounts[stage] ?: 0}회",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cravingAccent(stage),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarStageChart(
    buckets: List<CravingCalendarBucket>,
    view: String,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
) {
    val selection = MaterialTheme.colorScheme.onSurface
    val accents = CravingStage.entries.associateWith { stage -> cravingAccent(stage) }
    val maximum = stageSampleMaximum(view).toFloat()
    val hourly = view == DashboardRepository.CALENDAR_VIEW_DAY
    val yAxisTitle = if (hourly) {
        "Y축 · 시간별 측정 횟수 (0–360회)"
    } else {
        "Y축 · 일별 측정 횟수 (0–8,640회)"
    }
    val axisLabels = if (hourly) {
        listOf("360", "180", "0")
    } else {
        listOf("8,640", "4,320", "0")
    }
    ChartYAxisTitle(yAxisTitle)
    Row(modifier = Modifier.fillMaxWidth()) {
        AxisLabels(axisLabels)
        Column(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight)
                    .semantics {
                        contentDescription =
                            "선택한 기간 갈망 단계 누적 막대그래프, $yAxisTitle"
                    }
                    .pointerInput(buckets.size) {
                        detectTapGestures { offset ->
                            val slot = size.width.toFloat() / buckets.size.coerceAtLeast(1)
                            onSelect((offset.x / slot).toInt().coerceIn(0, buckets.lastIndex))
                        }
                    },
            ) {
                if (buckets.isEmpty()) return@Canvas
                val slot = size.width / buckets.size
                val barWidth = (slot * 0.64f).coerceAtLeast(2f)
                buckets.forEachIndexed { index, bucket ->
                    val left = index * slot + (slot - barWidth) / 2f
                    if (bucket.hasStageData) {
                        var bottom = size.height
                        CravingStage.entries.forEach { stage ->
                            val count = bucket.stageCounts[stage] ?: 0
                            if (count <= 0) return@forEach
                            val height = (size.height * count.toFloat() / maximum)
                                .coerceAtMost(bottom)
                            drawRect(
                                color = accents.getValue(stage),
                                topLeft = Offset(left, bottom - height),
                                size = Size(barWidth, height),
                            )
                            bottom -= height
                        }
                    }
                    if (index == selectedIndex && bucket.hasStageData) {
                        drawRoundRect(
                            color = selection,
                            topLeft = Offset(left - 2f, 0f),
                            size = Size(barWidth + 4f, size.height),
                            cornerRadius = CornerRadius(3.dp.toPx()),
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
private fun CalendarEventSection(
    buckets: List<CravingCalendarBucket>,
    view: String,
    errorMessage: String?,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
) {
    SectionBlock(label = "갈망 이벤트") {
        when {
            errorMessage != null -> EmptyLine(errorMessage)
            buckets.none { it.hasPredictionData } -> EmptyLine("선택한 기간에 측정 기록이 없어요.")
            else -> {
                CalendarValueBars(
                    buckets = buckets,
                    values = buckets.map { it.eventCount.toFloat() },
                    present = buckets.map { it.hasPredictionData },
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                    description = "선택한 기간 갈망 이벤트 막대그래프",
                    yAxisTitle = "Y축 · 갈망 이벤트 발생 횟수 (건)",
                )
                selectedIndex?.let { index ->
                    buckets.getOrNull(index)?.let { bucket ->
                        Text(
                            "${bucket.label} · " +
                                if (bucket.hasPredictionData) "${bucket.eventCount}건" else "측정 데이터 없음",
                        )
                    }
                }
                Text(
                    if (view == DashboardRepository.CALENDAR_VIEW_DAY) "시간별 이벤트" else "일별 이벤트",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CalendarAuqSection(
    buckets: List<CravingCalendarBucket>,
    view: String,
    errorMessage: String?,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
) {
    SectionBlock(label = "자기설문") {
        when {
            errorMessage != null -> EmptyLine(errorMessage)
            buckets.none { it.hasAuqData } -> EmptyLine("선택한 기간에 응답한 자기설문이 없어요.")
            else -> {
                CalendarValueBars(
                    buckets = buckets,
                    values = buckets.map { it.auqAverageScore ?: 0f },
                    present = buckets.map { it.hasAuqData },
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                    description = "선택한 기간 자기설문 평균 막대그래프",
                    yAxisTitle = "Y축 · 자기설문 평균 점수 (0–48점)",
                    fixedMaximum = Auq.SCALE_MAX.toFloat(),
                )
                selectedIndex?.let { index ->
                    buckets.getOrNull(index)?.let { bucket ->
                        Text(
                            if (bucket.hasAuqData) {
                                "${bucket.label} · 평균 ${bucket.auqAverageScore?.roundToInt()}/${Auq.SCALE_MAX}" +
                                    " · 응답 ${bucket.auqResponseCount}회"
                            } else {
                                "${bucket.label} · 응답 없음"
                            },
                        )
                    }
                }
                Text(
                    if (view == DashboardRepository.CALENDAR_VIEW_DAY) "시간별 평균 (0-48)" else "일별 평균 (0-48)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CalendarValueBars(
    buckets: List<CravingCalendarBucket>,
    values: List<Float>,
    present: List<Boolean>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    yAxisTitle: String,
    fixedMaximum: Float? = null,
) {
    val bar = MaterialTheme.colorScheme.primary
    val selection = MaterialTheme.colorScheme.onSurface
    val maximum = fixedMaximum ?: values
        .filterIndexed { index, _ -> present.getOrElse(index) { false } }
        .maxOrNull()
        ?.coerceAtLeast(1f)
        ?: 1f
    val axisLabels = when {
        fixedMaximum != null ->
            listOf(
                fixedMaximum.roundToInt().toString(),
                (fixedMaximum / 2f).roundToInt().toString(),
                "0",
            )
        maximum <= 1f -> listOf("1", "", "0")
        else -> listOf(
            maximum.roundToInt().toString(),
            (maximum / 2f).roundToInt().toString(),
            "0",
        )
    }

    ChartYAxisTitle(yAxisTitle)
    Row(modifier = Modifier.fillMaxWidth()) {
        AxisLabels(axisLabels)
        Column(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(EventAuqChartHeight)
                    .semantics {
                        contentDescription = "$description, $yAxisTitle"
                    }
                    .pointerInput(buckets.size) {
                        detectTapGestures { offset ->
                            val slot = size.width.toFloat() / buckets.size.coerceAtLeast(1)
                            onSelect((offset.x / slot).toInt().coerceIn(0, buckets.lastIndex))
                        }
                    },
            ) {
                if (buckets.isEmpty()) return@Canvas
                val slot = size.width / buckets.size
                val barWidth = (slot * 0.58f).coerceAtLeast(2f)
                buckets.forEachIndexed { index, _ ->
                    val left = index * slot + (slot - barWidth) / 2f
                    if (present.getOrElse(index) { false }) {
                        val value = values.getOrElse(index) { 0f }.coerceAtLeast(0f)
                        if (value == 0f) {
                            drawCircle(
                                bar,
                                3.dp.toPx(),
                                Offset(left + barWidth / 2f, size.height - 3.dp.toPx()),
                            )
                        } else {
                            val height = size.height * (value / maximum).coerceIn(0f, 1f)
                            drawRoundRect(
                                color = bar,
                                topLeft = Offset(left, size.height - height),
                                size = Size(barWidth, height),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                            )
                        }
                    }
                    if (index == selectedIndex) {
                        drawRoundRect(
                            color = selection,
                            topLeft = Offset(left - 2f, 0f),
                            size = Size(barWidth + 4f, size.height),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            AxisRow(edgeLabels(buckets.map { it.label }))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Legacy compatibility renderers for GET /api/me/craving-dashboard.
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
    val selection = MaterialTheme.colorScheme.onSurface
    val stageColors = CravingStage.entries.associateWith { cravingAccent(it) }

    ChartYAxisTitle("Y축 · 시간별 측정 횟수 (0–360회)")
    Row(modifier = Modifier.fillMaxWidth()) {
        AxisLabels(listOf("360", "180", "0"))
        Column(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight)
                    .semantics {
                        contentDescription =
                            "오늘 24시간 갈망 단계 누적 막대그래프, 시간별 최대 360회"
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
                    if (bucket.hasData) {
                        var bottom = size.height
                        for (stage in CravingStage.entries) {
                            val count = bucket.stageCounts[stage] ?: 0
                            if (count <= 0) continue
                            val height =
                                (
                                    size.height * count.toFloat() /
                                        HourlyStageSampleMaximum.toFloat()
                                    ).coerceAtMost(bottom)
                            drawRect(
                                color = stageColors.getValue(stage),
                                topLeft = Offset(left, bottom - height),
                                size = Size(barWidth, height),
                            )
                            bottom -= height
                        }
                    }

                    if (bucket.hour == selectedHour && bucket.hasData) {
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
            AxisRow(listOf("00시", "06시", "12시", "18시", "23시"))
        }
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
                text = "측정 ${bucket.countedSamples}회 / 최대 360회",
                style = MaterialTheme.typography.bodyMedium,
            )
            CravingStage.entries.forEach { stage ->
                Text(
                    text = "${stage.label} ${bucket.stageCounts[stage] ?: 0}회",
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
// 4 · 자기설문
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
        label = "자기설문 평균",
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

            buckets.none { it.hasData } -> EmptyLine("아직 응답한 자기설문이 없어요.")

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
                        contentDescription = "자기설문 평균 막대그래프, 막대를 눌러 구간을 선택"
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
// 5 · 실시간 신호 데이터 (PPG/EDA)
// ---------------------------------------------------------------------------------------------

@Composable
private fun PpgSection(
    live: LivePpgState,
    watchState: WatchConnectionState,
) {
    SectionBlock(label = "실시간 신호") {
        Text(
            text = "Watch · 최근 ${LivePpgWindow.WINDOW_MS / 1000}초",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        LivePpgBlock(live = live, watchState = watchState)
    }
}

/** Live only while samples keep arriving; a stalled buffer reverts to the waiting copy. */
@Composable
private fun LivePpgBlock(live: LivePpgState, watchState: WatchConnectionState) {
    when (live) {
        is LivePpgState.Streaming -> {
            Text("PPG", style = MaterialTheme.typography.labelLarge)
            if (live.trace.samples.isEmpty()) {
                EmptyLine("PPG 신호가 아직 도착하지 않았어요.")
            } else {
                PpgWaveform(
                    samples = live.trace.samples,
                    accent = MaterialTheme.colorScheme.primary,
                    description = "실시간 PPG 신호 그래프, ${live.trace.samples.size}개 지점",
                )
            }
            Text("EDA", style = MaterialTheme.typography.labelLarge)
            if (live.trace.edaSamples.isEmpty()) {
                EmptyLine("EDA 신호가 아직 도착하지 않았어요.")
            } else {
                PpgWaveform(
                    samples = live.trace.edaSamples,
                    accent = MaterialTheme.colorScheme.secondary,
                    description = "실시간 EDA 신호 그래프, ${live.trace.edaSamples.size}개 지점",
                )
            }
            Text(
                text = "PPG ${live.trace.samples.size}개 · EDA ${live.trace.edaSamples.size}개 표시 지점 " +
                    "· ${clockOf(live.trace.latestAtMs)} 기준 · 표시용 채널별 정규화",
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

/** Shared by the two independently normalized live channels. */
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
                    .height(SignalChartHeight)
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
private fun ChartYAxisTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = text },
    )
}

@Composable
private fun AxisLabels(labels: List<String>, height: androidx.compose.ui.unit.Dp = ChartHeight) {
    Column(
        modifier = Modifier
            .width(AxisLabelWidth)
            .height(height),
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

private fun stageSampleMaximum(view: String): Int =
    if (view == DashboardRepository.CALENDAR_VIEW_DAY) {
        HourlyStageSampleMaximum
    } else {
        DailyStageSampleMaximum
    }

private fun formatCount(value: Int): String =
    "%,d".format(value)

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
