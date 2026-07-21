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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

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
            val eda        by SensorState.edaText.collectAsState()
            val skinTemp   by SensorState.skinTempText.collectAsState()
            val cravingClass by SensorState.cravingClass.collectAsState()
            val cravingText by SensorState.cravingText.collectAsState()
            val alertLevel by SensorState.alertLevel.collectAsState()
            val alertText by SensorState.alertText.collectAsState()

            WatchScreen(
                status     = status,
                isTracking = isTracking,
                cravingClass = cravingClass,
                cravingText = cravingText,
                alertLevel = alertLevel,
                alertText = alertText,
                hr         = hr,
                eda        = eda,
                skinTemp   = skinTemp,
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
    cravingClass: Int?,
    cravingText: String,
    alertLevel: String,
    alertText: String,
    hr: String,
    eda: String,
    skinTemp: String,
    onToggle: () -> Unit
) {
    val cravingColor = when (cravingClass) {
        0 -> Color(0xFF10B981)
        1 -> Color(0xFFFF4D5E)
        else -> Color(0xFF7E8A95)
    }
    val alertColor = when (alertLevel) {
        "recommend" -> Color(0xFFFFB020)
        "required" -> Color(0xFFFF4D5E)
        else -> Color(0xFF7E8A95)
    }

    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF070A0C)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(horizontal = 15.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .background(Color(0xFF12181E), RoundedCornerShape(8.dp))
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isTracking) "LIVE" else "WAIT", fontSize = 9.sp, color = if (isTracking) Color(0xFF10B981) else Color(0xFF7E8A95), fontWeight = FontWeight.Bold)
                    Box(modifier = Modifier.size(7.dp).background(if (isTracking) Color(0xFF10B981) else Color(0xFF7E8A95), CircleShape))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .background(Color(0xFF12181E), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("STATE", fontSize = 9.sp, color = cravingColor, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .background(cravingColor.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(watchStateBadge(cravingClass), fontSize = 18.sp, color = cravingColor, fontWeight = FontWeight.Bold)
                        }
                        Text(cravingText, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .background(alertColor.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = alertText,
                        fontSize = 10.sp,
                        color = alertColor,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                Text(status, fontSize = 8.sp, color = Color(0xFF7E8A95), textAlign = TextAlign.Center)

                Button(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(0.62f),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (isTracking) Color(0xFFFF4D5E) else Color(0xFF10B981),
                        contentColor = Color.White
                    )
                ) {
                    Text(if (isTracking) "중지" else "시작", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                if (isTracking) {
                    Spacer(Modifier.height(1.dp))
                    SensorRow("HR",       hr,       Color(0xFF10B981))
                    SensorRow("EDA",      eda,      Color(0xFFFFB020))
                    SensorRow("TEMP",     skinTemp, Color(0xFFFF7A59))
                }
            }
        }
    }
}

private fun watchStateBadge(cravingClass: Int?): String =
    when (cravingClass) {
        0 -> "낮음"
        1 -> "높음"
        else -> "대기"
    }

@Composable
fun SensorRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.84f)
            .background(Color(0xFF12181E), RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 9.sp, color = color, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 9.sp, color = Color(0xFFE8EDF2), textAlign = TextAlign.End, maxLines = 1)
    }
}
