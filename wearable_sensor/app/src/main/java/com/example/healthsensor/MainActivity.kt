/**
 * MainActivity.kt — 폰 앱 메인 화면
 *
 * 15개 센서 채널의 실시간 차트와 CSV 저장 기능을 제공한다.
 *   - HR (심박수), PPG Green/IR/Red, EDA, Accel X/Y/Z, Skin Temp,
 *     ECG, SpO2, BIA Fat/BMI/Muscle/Water, Sweat Loss
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
                viewModel.allEcgRaw.forEach        { (ts, v) -> w.write("ECG,$ts,$v\n") }
                viewModel.allSpo2Raw.forEach       { (ts, v) -> w.write("SPO2,$ts,$v\n") }
                viewModel.allBiaFatRaw.forEach     { (ts, v) -> w.write("BIA_FAT,$ts,$v\n") }
                viewModel.allBiaBmiRaw.forEach     { (ts, v) -> w.write("BIA_BMR,$ts,$v\n") }
                viewModel.allBiaMuscleRaw.forEach  { (ts, v) -> w.write("BIA_MUSCLE,$ts,$v\n") }
                viewModel.allBiaWaterRaw.forEach   { (ts, v) -> w.write("BIA_WATER,$ts,$v\n") }
                viewModel.allSweatLossRaw.forEach  { (ts, v) -> w.write("SWEAT_LOSS,$ts,$v\n") }
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
    val ecgPoints       by viewModel.ecgPoints.collectAsState()
    val spo2Points      by viewModel.spo2Points.collectAsState()
    val biaFatPoints    by viewModel.biaFatPoints.collectAsState()
    val biaBmiPoints    by viewModel.biaBmiPoints.collectAsState()
    val biaMusclePoints by viewModel.biaMusclePoints.collectAsState()
    val biaWaterPoints  by viewModel.biaWaterPoints.collectAsState()
    val sweatLossPoints by viewModel.sweatLossPoints.collectAsState()
    val isReceiving     by viewModel.isReceiving.collectAsState()

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

        SensorChartCard(title = "심박수 (HR)",        unit = "bpm",  lineColor = Color(0xFF4CAF50), points = hrPoints,       entries = viewModel.toMpEntries(hrPoints))
        SensorChartCard(title = "PPG Raw (Green)",   unit = "raw",  lineColor = Color(0xFF2196F3), points = ppgPoints,      entries = viewModel.toMpEntries(ppgPoints))
        SensorChartCard(title = "PPG Raw (IR)",      unit = "raw",  lineColor = Color(0xFF880E4F), points = ppgIrPoints,    entries = viewModel.toMpEntries(ppgIrPoints))
        SensorChartCard(title = "PPG Raw (Red)",     unit = "raw",  lineColor = Color(0xFFF44336), points = ppgRedPoints,   entries = viewModel.toMpEntries(ppgRedPoints))
        SensorChartCard(title = "EDA (피부전도도)",   unit = "μS",   lineColor = Color(0xFFFF9800), points = edaPoints,      entries = viewModel.toMpEntries(edaPoints))
        SensorChartCard(title = "가속도 X축",         unit = "m/s²", lineColor = Color(0xFF9C27B0), points = accelXPoints,   entries = viewModel.toMpEntries(accelXPoints))
        SensorChartCard(title = "가속도 Y축",         unit = "m/s²", lineColor = Color(0xFF673AB7), points = accelYPoints,   entries = viewModel.toMpEntries(accelYPoints))
        SensorChartCard(title = "가속도 Z축",         unit = "m/s²", lineColor = Color(0xFF3F51B5), points = accelZPoints,   entries = viewModel.toMpEntries(accelZPoints))
        SensorChartCard(title = "피부 온도 (SkinTemp)", unit = "°C",  lineColor = Color(0xFFE91E63), points = skinTempPoints, entries = viewModel.toMpEntries(skinTempPoints))
        SensorChartCard(title = "ECG (승인 필요)",    unit = "raw", lineColor = Color(0xFF00BCD4), points = ecgPoints,       entries = viewModel.toMpEntries(ecgPoints))
        SensorChartCard(title = "SpO2 (혈중 산소)",  unit = "%",   lineColor = Color(0xFF1565C0), points = spo2Points,      entries = viewModel.toMpEntries(spo2Points))
        SensorChartCard(title = "체지방률 (BIA)",    unit = "%",   lineColor = Color(0xFFE65100), points = biaFatPoints,    entries = viewModel.toMpEntries(biaFatPoints))
        SensorChartCard(title = "기초대사량 (BIA)",    unit = "kcal",lineColor = Color(0xFFBF360C), points = biaBmiPoints,    entries = viewModel.toMpEntries(biaBmiPoints))
        SensorChartCard(title = "골격근량 (BIA)",    unit = "kg",  lineColor = Color(0xFF33691E), points = biaMusclePoints, entries = viewModel.toMpEntries(biaMusclePoints))
        SensorChartCard(title = "체수분 (BIA)",      unit = "%",   lineColor = Color(0xFF006064), points = biaWaterPoints,  entries = viewModel.toMpEntries(biaWaterPoints))
        SensorChartCard(title = "발한량",            unit = "ml",  lineColor = Color(0xFF4A148C), points = sweatLossPoints, entries = viewModel.toMpEntries(sweatLossPoints))

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
    entries: List<com.github.mikephil.charting.data.Entry>
) {
    val latestValue = points.lastOrNull()?.value
    val colorArgb = lineColor.toArgb()
    val visibleWindowUnits = 70f   // SensorViewModel x축 1칸 = 100ms, 70칸 = 7초
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
                    Text("%.2f $unit".format(it), fontSize = 13.sp, color = lineColor, fontWeight = FontWeight.Medium)
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
                                mode = LineDataSet.Mode.LINEAR
                                axisDependency = YAxis.AxisDependency.LEFT
                            }.also { chart.data = LineData(it) }
                        } else {
                            (chart.data.getDataSetByIndex(0) as LineDataSet).apply {
                                values = entries.toMutableList()
                                color = colorArgb
                            }
                        }
                        chart.setVisibleXRangeMaximum(visibleWindowUnits)
                        val latestX = chart.data.xMax
                        val visibleMinX = max(entries.first().x, latestX - visibleWindowUnits)
                        val visibleEntries = entries.filter { it.x >= visibleMinX && it.x <= latestX }
                        chart.axisLeft.applyVisibleScale(visibleEntries, axisScaleState)
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
