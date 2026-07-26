package com.neurotruth.mobile.wear

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/** Three-page, display-safe Watch companion. Measurement initiation remains Phone-owned. */
class MainActivity : ComponentActivity() {
    private var pendingConfirmationRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handledConfirmation = consumeConfirmationIntent(intent)
        if (!handledConfirmation) {
            val missing = (SENSOR_PERMISSIONS + OPTIONAL_PERMISSIONS).filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                // Permission setup alone never starts measurement. It only makes the later
                // Phone-requested confirmation notification and health FGS path available.
                requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
            }
        }
        setContent { WatchPages(onStop = { SensorTrackingService.stop(this) }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeConfirmationIntent(intent)
    }

    private fun consumeConfirmationIntent(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_CONFIRM_START, false) != true) return false
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return false
        pendingConfirmationRequestId = requestId
        if (hasSensorPermissions()) {
            SensorTrackingService.start(this, requestId)
            pendingConfirmationRequestId = null
        } else {
            requestPermissions(SENSOR_PERMISSIONS, PERMISSION_REQUEST)
        }
        return true
    }

    private fun hasSensorPermissions(): Boolean =
        SENSOR_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        val requestId = pendingConfirmationRequestId
        if (requestId == null) {
            val sensorPermissionDenied = permissions.zip(grantResults.toTypedArray()).any {
                (permission, result) ->
                permission in SENSOR_PERMISSIONS && result != PackageManager.PERMISSION_GRANTED
            }
            if (sensorPermissionDenied) SensorState.status.value = "권한 거부됨"
            return
        }
        if (hasSensorPermissions()) {
            SensorTrackingService.start(this, requestId)
        } else {
            SensorTrackingService.sendControlStatus(
                this,
                requestId,
                "error",
                "permission_denied",
            )
            SensorState.status.value = "권한 거부됨"
        }
        pendingConfirmationRequestId = null
    }

    companion object {
        const val EXTRA_CONFIRM_START = "confirmMeasurementStart"
        const val EXTRA_REQUEST_ID = "measurementRequestId"
        private const val PERMISSION_REQUEST = 100
        private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val READ_ADDITIONAL_HEALTH_DATA =
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"

        val SENSOR_PERMISSIONS = buildList {
            add(android.Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.TIRAMISU..35) {
                add(android.Manifest.permission.BODY_SENSORS_BACKGROUND)
            }
            add(READ_ADDITIONAL_HEALTH_DATA)
            if (Build.VERSION.SDK_INT >= 36) add(READ_HEART_RATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(android.Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }.toTypedArray()

        val OPTIONAL_PERMISSIONS = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WatchPages(onStop: () -> Unit) {
    val isTracking by SensorState.isTracking.collectAsState()
    val status by SensorState.status.collectAsState()
    val stage by SensorState.cravingText.collectAsState()
    val stageCode by SensorState.stageCode.collectAsState()
    val timeline by SensorState.stageTimeline.collectAsState()
    val eventCount by SensorState.todayEventCount.collectAsState()
    val auqScore by SensorState.todayAuqScore.collectAsState()
    val pager = rememberPagerState(pageCount = { 3 })

    MaterialTheme {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            WatchPage {
                Text(
                    text = "${page + 1} / 3",
                    fontSize = 9.sp,
                    color = Color(0xFF7E8A95),
                )
                when (page) {
                    0 -> MeasurementPage(isTracking, status, stage, stageCode, onStop)
                    1 -> TimelinePage(timeline)
                    else -> TodaySummaryPage(eventCount, auqScore)
                }
            }
        }
    }
}

@Composable
private fun WatchPage(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF070A0C)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun MeasurementPage(
    isTracking: Boolean,
    status: String,
    stage: String,
    stageCode: String?,
    onStop: () -> Unit,
) {
    Text("현재 상태", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text(
        text = if (stage == SensorState.NO_VALUE) "측정 데이터 없음" else stage,
        color = if (stage == SensorState.NO_VALUE) Color(0xFF7E8A95) else stageColor(stageCode),
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = if (isTracking) status else "휴대폰에서 측정을 시작해 주세요",
        color = Color(0xFFB8C2CC),
        fontSize = 10.sp,
        textAlign = TextAlign.Center,
    )
    if (isTracking) {
        Button(
            onClick = onStop,
            modifier = Modifier.width(104.dp).height(40.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC94C5C)),
        ) {
            Text(
                text = "측정 중지",
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TimelinePage(samples: List<StageSample>) {
    Text("최근 1시간", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    if (samples.isEmpty()) {
        Text("측정 데이터 없음", color = Color(0xFF7E8A95), fontSize = 10.sp)
        return
    }
    val ordered = samples.sortedBy { it.timestampMs }
    val domainEndMs = maxOf(System.currentTimeMillis(), ordered.last().timestampMs)
    val domainStartMs = domainEndMs - ONE_HOUR_MS
    val visible = ordered.filter { it.timestampMs >= domainStartMs }
    Row(
        modifier = Modifier.fillMaxWidth().height(96.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(96.dp)
                .background(Color(0xFF12181E), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            val span = ONE_HOUR_MS.toFloat()
            val runWidth = 7.dp.toPx()
            val pointRadius = runWidth / 2f
            fun x(timestampMs: Long): Float {
                val raw = size.width * (timestampMs - domainStartMs).toFloat() / span
                return raw.coerceIn(pointRadius, (size.width - pointRadius).coerceAtLeast(pointRadius))
            }
            fun y(code: String): Float {
                val band = when (code) {
                    "low" -> 3f
                    "observe" -> 2f
                    "caution" -> 1f
                    "high" -> 0f
                    else -> 1.5f
                }
                return size.height * (band + 0.5f) / 4f
            }
            listOf("high", "caution", "observe", "low").forEach { code ->
                drawLine(
                    color = stageColor(code).copy(alpha = 0.16f),
                    start = Offset(0f, y(code)),
                    end = Offset(size.width, y(code)),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            visible.zipWithNext().forEach { (left, right) ->
                if (right.timestampMs - left.timestampMs > 20_000L) return@forEach
                val x1 = x(left.timestampMs)
                val x2 = x(right.timestampMs)
                val leftY = y(left.stageCode)
                val rightY = y(right.stageCode)
                drawLine(
                    color = stageColor(left.stageCode),
                    start = Offset(x1, leftY),
                    end = Offset(x2, leftY),
                    strokeWidth = runWidth,
                    cap = StrokeCap.Round,
                )
                if (left.stageCode != right.stageCode) {
                    val middleY = (leftY + rightY) / 2f
                    drawLine(
                        color = stageColor(left.stageCode).copy(alpha = 0.8f),
                        start = Offset(x2, leftY),
                        end = Offset(x2, middleY),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = stageColor(right.stageCode).copy(alpha = 0.8f),
                        start = Offset(x2, middleY),
                        end = Offset(x2, rightY),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            visible.forEach { sample ->
                drawCircle(
                    color = stageColor(sample.stageCode),
                    radius = pointRadius,
                    center = Offset(x(sample.timestampMs), y(sample.stageCode)),
                )
            }
        }
        Column(
            modifier = Modifier.width(30.dp).height(96.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text("위험", color = stageColor("high"), fontSize = 8.sp)
            Text("주의", color = stageColor("caution"), fontSize = 8.sp)
            Text("관찰", color = stageColor("observe"), fontSize = 8.sp)
            Text("안정", color = stageColor("low"), fontSize = 8.sp)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("-60분", color = Color(0xFF7E8A95), fontSize = 8.sp)
        Text("현재", color = Color(0xFF7E8A95), fontSize = 8.sp)
    }
}

@Composable
private fun TodaySummaryPage(eventCount: Int?, auqScore: Float?) {
    Text("오늘 요약", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    SummaryCard("갈망 이벤트", eventCount?.let { "${it}건" } ?: "데이터 없음")
    SummaryCard("자기설문 AUQ", auqScore?.let { "%.1f / 48".format(it) } ?: "응답 없음")
}

@Composable
private fun SummaryCard(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF12181E), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Text(label, color = Color(0xFF7E8A95), fontSize = 9.sp)
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

private fun stageColor(code: String?): Color = when (code) {
    "low" -> Color(0xFF267A73)
    "observe" -> Color(0xFF59A7A0)
    "caution" -> Color(0xFFD68A22)
    "high" -> Color(0xFFC94C5C)
    else -> Color(0xFF7E8A95)
}

private const val ONE_HOUR_MS = 60L * 60L * 1000L
