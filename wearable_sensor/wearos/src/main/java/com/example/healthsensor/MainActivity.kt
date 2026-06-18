/**
 * MainActivity.kt — 워치 앱 UI
 *
 * 권한 요청 및 SensorTrackingService 시작/중지만 담당한다.
 * 센서값은 SensorState를 구독하여 실시간으로 표시한다.
 */
package com.example.healthsensor

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    companion object {
        private const val PERM_REQ = 100
        private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val READ_ADDITIONAL_HEALTH_DATA =
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"

        private val SENSOR_PERMISSIONS = buildList {
            add(android.Manifest.permission.BODY_SENSORS)
            add(READ_ADDITIONAL_HEALTH_DATA)
            if (Build.VERSION.SDK_INT >= 36) {
                add(READ_HEART_RATE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                add(android.Manifest.permission.ACTIVITY_RECOGNITION)
        }.toTypedArray()
        private val OPTIONAL_PERMISSIONS = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = (SENSOR_PERMISSIONS + OPTIONAL_PERMISSIONS).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERM_REQ)
        }

        setContent {
            val isTracking by SensorState.isTracking.collectAsState()
            val status     by SensorState.status.collectAsState()
            val hr         by SensorState.hrText.collectAsState()
            val ppg        by SensorState.ppgText.collectAsState()
            val ppgIr      by SensorState.ppgIrText.collectAsState()
            val ppgRed     by SensorState.ppgRedText.collectAsState()
            val eda        by SensorState.edaText.collectAsState()
            val accelX     by SensorState.accelXText.collectAsState()
            val accelY     by SensorState.accelYText.collectAsState()
            val accelZ     by SensorState.accelZText.collectAsState()
            val skinTemp   by SensorState.skinTempText.collectAsState()
            val ecg        by SensorState.ecgText.collectAsState()
            val spo2       by SensorState.spo2Text.collectAsState()
            val bia        by SensorState.biaText.collectAsState()
            val sweatLoss  by SensorState.sweatLossText.collectAsState()

            WatchScreen(
                status     = status,
                isTracking = isTracking,
                hr         = hr,
                ppg        = ppg,
                ppgIr      = ppgIr,
                ppgRed     = ppgRed,
                eda        = eda,
                accelX     = accelX,
                accelY     = accelY,
                accelZ     = accelZ,
                skinTemp   = skinTemp,
                ecg        = ecg,
                spo2       = spo2,
                bia        = bia,
                sweatLoss  = sweatLoss,
                onToggle   = {
                    if (isTracking) {
                        SensorTrackingService.stop(this@MainActivity)
                    } else if (hasSensorPermissions()) {
                        SensorTrackingService.start(this@MainActivity)
                    } else {
                        requestPermissions(SENSOR_PERMISSIONS, PERM_REQ)
                    }
                }
            )
        }
    }

    private fun hasSensorPermissions(): Boolean =
        SENSOR_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val deniedSensorPermission = permissions.zip(grantResults.toTypedArray()).any { (permission, result) ->
            permission in SENSOR_PERMISSIONS && result != PackageManager.PERMISSION_GRANTED
        }
        if (requestCode == PERM_REQ && deniedSensorPermission) {
            SensorState.status.value = "권한 거부됨 - 설정에서 허용 필요"
        }
    }
}

@Composable
fun WatchScreen(
    status: String,
    isTracking: Boolean,
    hr: String,
    ppg: String,
    ppgIr: String,
    ppgRed: String,
    eda: String,
    accelX: String,
    accelY: String,
    accelZ: String,
    skinTemp: String,
    ecg: String,
    spo2: String,
    bia: String,
    sweatLoss: String,
    onToggle: () -> Unit
) {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(status, fontSize = 9.sp, color = Color(0xFF888888))
                Button(onClick = onToggle, modifier = Modifier.fillMaxWidth(0.65f)) {
                    Text(if (isTracking) "■ 중지" else "▶ 시작", fontSize = 13.sp)
                }
                if (isTracking) {
                    Spacer(Modifier.height(2.dp))
                    SensorRow("HR",       hr,       Color(0xFF4CAF50))
                    SensorRow("PPG G",    ppg,      Color(0xFF2196F3))
                    SensorRow("PPG IR",   ppgIr,    Color(0xFFAD1457))
                    SensorRow("PPG R",    ppgRed,   Color(0xFFF44336))
                    SensorRow("EDA",      eda,      Color(0xFFFF9800))
                    SensorRow("ACC X",    accelX,   Color(0xFF9C27B0))
                    SensorRow("ACC Y",    accelY,   Color(0xFF673AB7))
                    SensorRow("ACC Z",    accelZ,   Color(0xFF3F51B5))
                    SensorRow("TEMP",     skinTemp, Color(0xFFE91E63))
                    SensorRow("ECG",      ecg,       Color(0xFF00BCD4))
                    SensorRow("SpO2",     spo2,      Color(0xFF1565C0))
                    SensorRow("BIA",      bia,       Color(0xFFE65100))
                    SensorRow("땀",       sweatLoss, Color(0xFF4A148C))
                }
            }
        }
    }
}

@Composable
fun SensorRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(0.85f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 10.sp, color = Color.White, textAlign = TextAlign.End)
    }
}
