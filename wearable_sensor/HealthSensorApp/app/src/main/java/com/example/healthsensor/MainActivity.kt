/**
 * MainActivity.kt — 폰 앱 메인 화면
 *
 * HR, PPG Green/IR/Red, EDA, Accel X/Y/Z, SkinTemp 실시간 차트,
 * CSV 저장, 10초 윈도우 서버 전송 기능을 제공한다.
 * MPAndroidChart를 AndroidView로 임베드하여 고주파 데이터를 효율적으로 렌더링한다.
 */
package com.example.healthsensor

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private val viewModel: SensorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MonitorScreen(viewModel = viewModel, onSaveCsv = { saveToCsv() })
                }
            }
        }
    }

    private fun saveToCsv() {
        val dir   = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file  = File(dir, "sensor_$stamp.csv")
        try {
            FileWriter(file).use { w ->
                w.write("sensor,timestamp_ms,value\n")
                viewModel.allHrRaw.forEach       { (ts, v) -> w.write("HR,$ts,$v\n") }
                viewModel.allPpgRaw.forEach      { (ts, v) -> w.write("PPG_GREEN,$ts,$v\n") }
                viewModel.allPpgIrRaw.forEach    { (ts, v) -> w.write("PPG_IR,$ts,$v\n") }
                viewModel.allPpgRedRaw.forEach   { (ts, v) -> w.write("PPG_RED,$ts,$v\n") }
                viewModel.allEdaRaw.forEach      { (ts, v) -> w.write("EDA,$ts,$v\n") }
                viewModel.allAccelXRaw.forEach   { (ts, v) -> w.write("ACCEL_X,$ts,$v\n") }
                viewModel.allAccelYRaw.forEach   { (ts, v) -> w.write("ACCEL_Y,$ts,$v\n") }
                viewModel.allAccelZRaw.forEach   { (ts, v) -> w.write("ACCEL_Z,$ts,$v\n") }
                viewModel.allSkinTempRaw.forEach { (ts, v) -> w.write("SKIN_TEMP,$ts,$v\n") }
                viewModel.allCravingClassRaw.forEach { (ts, v) -> w.write("CRAVING_CLASS,$ts,$v\n") }
            }
            Toast.makeText(this, "저장: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun MonitorScreen(viewModel: SensorViewModel, onSaveCsv: () -> Unit) {
    val hrPoints       by viewModel.hrPoints.collectAsState()
    val ppgPoints      by viewModel.ppgPoints.collectAsState()
    val ppgIrPoints    by viewModel.ppgIrPoints.collectAsState()
    val ppgRedPoints   by viewModel.ppgRedPoints.collectAsState()
    val edaPoints      by viewModel.edaPoints.collectAsState()
    val accelXPoints   by viewModel.accelXPoints.collectAsState()
    val accelYPoints   by viewModel.accelYPoints.collectAsState()
    val accelZPoints   by viewModel.accelZPoints.collectAsState()
    val skinTempPoints by viewModel.skinTempPoints.collectAsState()
    val isReceiving     by viewModel.isReceiving.collectAsState()
    val serverUrl       by viewModel.serverUrl.collectAsState()
    val isUploadEnabled by viewModel.isUploadEnabled.collectAsState()
    val uploadStatus    by viewModel.uploadStatus.collectAsState()
    val predictionUrl   by viewModel.predictionUrl.collectAsState()
    val isPredictionReceiverEnabled by viewModel.isPredictionReceiverEnabled.collectAsState()
    val predictionStatus by viewModel.predictionStatus.collectAsState()
    val latestPrediction by viewModel.latestPrediction.collectAsState()
    val cravingClassPoints by viewModel.cravingClassPoints.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("헬스 센서 모니터", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier.size(10.dp).background(
                        if (isReceiving) Color(0xFF4CAF50) else Color(0xFFBBBBBB),
                        shape = CircleShape
                    )
                )
                Text(
                    text = if (isReceiving) "수신 중" else "대기 중",
                    fontSize = 12.sp,
                    color = if (isReceiving) Color(0xFF4CAF50) else Color.Gray
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("서버 전송", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = viewModel::updateServerUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("POST URL") },
                    placeholder = { Text("http://192.168.0.10:8000/sensor-window") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.setUploadEnabled(!isUploadEnabled) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isUploadEnabled) "전송 중지" else "1초 전송 시작")
                    }
                    Text(
                        text = if (isUploadEnabled) "10초 윈도우" else "대기",
                        fontSize = 12.sp,
                        color = if (isUploadEnabled) Color(0xFF4CAF50) else Color.Gray
                    )
                }
                Text(uploadStatus, fontSize = 11.sp, color = Color.Gray)
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("예측 수신", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = predictionUrl,
                    onValueChange = viewModel::updatePredictionUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Prediction SSE URL") },
                    placeholder = { Text("http://192.168.0.10:8000/prediction-stream") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.setPredictionReceiverEnabled(!isPredictionReceiverEnabled) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isPredictionReceiverEnabled) "수신 중지" else "예측 수신 시작")
                    }
                    Text(
                        text = latestPrediction?.let { "class ${it.cravingClass}" } ?: "class —",
                        fontSize = 12.sp,
                        color = when (latestPrediction?.cravingClass) {
                            0 -> Color(0xFF4CAF50)
                            1 -> Color(0xFFFF9800)
                            2 -> Color(0xFFF44336)
                            else -> Color.Gray
                        }
                    )
                }
                Text(predictionStatus, fontSize = 11.sp, color = Color.Gray)
            }
        }

        // 서버에서 돌아온 class 0/1/2는 센서와 같은 시간축에 step chart로 표시한다.
        SensorChartCard(
            title = "갈망 Class",
            unit = "",
            lineColor = Color(0xFF795548),
            points = cravingClassPoints,
            entries = viewModel.toMpEntries(cravingClassPoints),
            fixedYMin = -0.1f,
            fixedYMax = 2.1f,
            lineMode = LineDataSet.Mode.STEPPED
        )

        SensorChartCard(title = "심박수 (HR)",        unit = "bpm",  lineColor = Color(0xFF4CAF50), points = hrPoints,       entries = viewModel.toMpEntries(hrPoints))
        SensorChartCard(title = "PPG Raw (Green)",   unit = "raw",  lineColor = Color(0xFF2196F3), points = ppgPoints,      entries = viewModel.toMpEntries(ppgPoints))
        SensorChartCard(title = "PPG Raw (IR)",      unit = "raw",  lineColor = Color(0xFF880E4F), points = ppgIrPoints,    entries = viewModel.toMpEntries(ppgIrPoints))
        SensorChartCard(title = "PPG Raw (Red)",     unit = "raw",  lineColor = Color(0xFFF44336), points = ppgRedPoints,   entries = viewModel.toMpEntries(ppgRedPoints))
        SensorChartCard(title = "EDA (피부전도도)",   unit = "μS",   lineColor = Color(0xFFFF9800), points = edaPoints,      entries = viewModel.toMpEntries(edaPoints))
        SensorChartCard(title = "가속도 X축",         unit = "m/s²", lineColor = Color(0xFF9C27B0), points = accelXPoints,   entries = viewModel.toMpEntries(accelXPoints))
        SensorChartCard(title = "가속도 Y축",         unit = "m/s²", lineColor = Color(0xFF673AB7), points = accelYPoints,   entries = viewModel.toMpEntries(accelYPoints))
        SensorChartCard(title = "가속도 Z축",         unit = "m/s²", lineColor = Color(0xFF3F51B5), points = accelZPoints,   entries = viewModel.toMpEntries(accelZPoints))
        SensorChartCard(title = "피부 온도 (SkinTemp)", unit = "°C",  lineColor = Color(0xFFE91E63), points = skinTempPoints, entries = viewModel.toMpEntries(skinTempPoints))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.clearAll() }, modifier = Modifier.weight(1f)) {
                Text("초기화")
            }
            Button(onClick = onSaveCsv, modifier = Modifier.weight(1f)) {
                Text("CSV 저장")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SensorChartCard(
    title: String,
    unit: String,
    lineColor: Color,
    points: List<SensorPoint>,
    entries: List<com.github.mikephil.charting.data.Entry>,
    // class chart처럼 의미 범위가 고정된 경우에만 y축을 고정한다.
    fixedYMin: Float? = null,
    fixedYMax: Float? = null,
    lineMode: LineDataSet.Mode = LineDataSet.Mode.LINEAR
) {
    val latestValue = points.lastOrNull()?.value
    val colorArgb = lineColor.toArgb()
    val visibleWindowUnits = 100f   // SensorViewModel x축 1칸 = 100ms, 100칸 = 10초
    val axisScaleState = remember { AxisScaleState() }

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                latestValue?.let {
                    Text(formatLatestValue(it, unit), fontSize = 13.sp, color = lineColor, fontWeight = FontWeight.Medium)
                } ?: Text("—", fontSize = 13.sp, color = Color.Gray)
            }

            Spacer(Modifier.height(6.dp))

            AndroidView(
                factory = { ctx ->
                    LineChart(ctx).apply {
                        description.isEnabled = false
                        setTouchEnabled(false)
                        legend.isEnabled = false
                        axisRight.isEnabled = false
                        xAxis.isEnabled = false
                        isAutoScaleMinMaxEnabled = false
                        axisLeft.apply {
                            textSize = 9f
                            setDrawGridLines(true)
                            gridColor = android.graphics.Color.argb(40, 128, 128, 128)
                        }
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setNoDataText("워치에서 측정을 시작하세요")
                        setNoDataTextColor(android.graphics.Color.GRAY)
                    }
                },
                update = { chart ->
                    if (entries.isEmpty()) {
                        axisScaleState.reset()
                        chart.clear()
                        chart.invalidate()
                    } else {
                        val dataSet = if (chart.data == null || chart.data.dataSetCount == 0) {
                            LineDataSet(entries.toMutableList(), title).apply {
                                color = colorArgb
                                setDrawValues(false)
                                setDrawCircles(false)
                                lineWidth = 1.5f
                                mode = lineMode
                                axisDependency = YAxis.AxisDependency.LEFT
                            }.also { chart.data = LineData(it) }
                        } else {
                            (chart.data.getDataSetByIndex(0) as LineDataSet).apply {
                                values = entries.toMutableList()
                                color = colorArgb
                                mode = lineMode
                            }
                        }
                        chart.setVisibleXRangeMaximum(visibleWindowUnits)
                        val latestX = chart.data.xMax
                        val visibleMinX = max(entries.first().x, latestX - visibleWindowUnits)
                        val visibleEntries = entries.filter { it.x >= visibleMinX && it.x <= latestX }
                        if (fixedYMin != null && fixedYMax != null) {
                            chart.axisLeft.axisMinimum = fixedYMin
                            chart.axisLeft.axisMaximum = fixedYMax
                        } else {
                            chart.axisLeft.applyVisibleScale(visibleEntries, axisScaleState)
                        }
                        chart.moveViewToX(latestX)
                        dataSet.notifyDataSetChanged()
                        chart.data.notifyDataChanged()
                        chart.notifyDataSetChanged()
                        chart.invalidate()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )

            Text("${points.size} 포인트", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))
        }
    }
}

private class AxisScaleState {
    var min: Float = Float.NaN
    var max: Float = Float.NaN

    fun reset() {
        min = Float.NaN
        max = Float.NaN
    }
}

private fun formatLatestValue(value: Float, unit: String): String =
    if (unit.isBlank()) "%.2f".format(value) else "%.2f $unit".format(value)

private fun com.github.mikephil.charting.components.YAxis.applyVisibleScale(
    visibleEntries: List<com.github.mikephil.charting.data.Entry>,
    state: AxisScaleState
) {
    if (visibleEntries.isEmpty()) return

    val values = visibleEntries.map { it.y }.filter { it.isFinite() }.sorted()
    if (values.isEmpty()) return

    val lower = visibleQuantile(values, 0.02f)
    val upper = visibleQuantile(values, 0.98f)
    val center = (lower + upper) / 2f
    val span = upper - lower
    val padding = if (span > 0f) {
        span * 0.08f
    } else {
        max(abs(center) * 0.0001f, 0.001f)
    }

    val targetMin = lower - padding
    val targetMax = upper + padding

    if (!state.min.isFinite() || !state.max.isFinite() || state.max <= state.min) {
        state.min = targetMin
        state.max = targetMax
    } else {
        state.min = smoothAxisBound(
            current = state.min,
            target = targetMin,
            expanding = targetMin < state.min
        )
        state.max = smoothAxisBound(
            current = state.max,
            target = targetMax,
            expanding = targetMax > state.max
        )
    }

    axisMinimum = state.min
    axisMaximum = state.max
}

private fun visibleQuantile(values: List<Float>, fraction: Float): Float {
    if (values.size < 30) return if (fraction < 0.5f) values.first() else values.last()

    val index = ((values.lastIndex) * fraction).toInt().coerceIn(0, values.lastIndex)
    return values[index]
}

private fun smoothAxisBound(current: Float, target: Float, expanding: Boolean): Float {
    val alpha = if (expanding) 0.45f else 0.10f
    return current + (target - current) * alpha
}
