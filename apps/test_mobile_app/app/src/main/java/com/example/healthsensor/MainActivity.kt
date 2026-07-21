/**
 * MainActivity.kt — 폰 앱 메인 화면
 *
 * HR, PPG Green/IR/Red, EDA, Accel X/Y/Z, SkinTemp 실시간 차트,
 * CSV 저장과 최근 20초 센서 윈도우의 10초 주기 서버 전송 기능을 제공한다.
 * MPAndroidChart를 AndroidView로 임베드하여 고주파 데이터를 효율적으로 렌더링한다.
 */
package com.example.healthsensor

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

private val HealthBackground = Color(0xFFF7F8FA)
private val HealthSurface = Color.White
private val HealthText = Color(0xFF172026)
private val HealthMuted = Color(0xFF66737D)
private val HealthLine = Color(0xFFE1E7EC)
private val HealthPrimary = Color(0xFF246B60)
private val HealthPrimarySoft = Color(0xFFE8F1EF)
private val HealthAccent = Color(0xFF486A8C)
private val HealthWarning = Color(0xFFD48A1F)
private val HealthDanger = Color(0xFFC83F4E)
private val HealthDangerSoft = Color(0xFFF9E7EA)
private val HealthIdle = Color(0xFF9AA4AC)

private enum class PatientTab(val label: String) {
    HOME("홈"), DASHBOARD("대시보드"), CHAT("챗봇")
}

private val healthColorScheme = lightColorScheme(
    primary = HealthPrimary,
    onPrimary = Color.White,
    primaryContainer = HealthPrimarySoft,
    onPrimaryContainer = HealthPrimary,
    secondary = Color(0xFF2D77C5),
    onSecondary = Color.White,
    tertiary = HealthAccent,
    onTertiary = Color.White,
    background = HealthBackground,
    onBackground = HealthText,
    surface = HealthSurface,
    onSurface = HealthText,
    surfaceVariant = Color(0xFFF0F4F7),
    onSurfaceVariant = HealthMuted,
    outline = HealthLine,
    error = HealthDanger
)

class MainActivity : ComponentActivity() {

    private val viewModel: SensorViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()
    private val rppgViewModel: RppgViewModel by viewModels()
    private val dashboardViewModel: PatientDashboardViewModel by viewModels()
    private var forceStateCheckLaunchCounter by mutableStateOf(0)
    private var suppressPermissionPrompts = false
    private var notificationPermissionRequestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenLaunch(intent)
        requestCriticalPermissionsIfNeeded()
        setContent {
            MaterialTheme(colorScheme = healthColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = HealthBackground) {
                    val authState by authViewModel.state.collectAsState()
                    val authLoading by authViewModel.isLoading.collectAsState()
                    val authMessage by authViewModel.message.collectAsState()
                    when (val state = authState) {
                        PatientAuthState.Restoring -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                        PatientAuthState.SignedOut -> PatientAuthScreen(
                            loading = authLoading,
                            message = authMessage,
                            onLogin = authViewModel::login,
                            onSignup = authViewModel::signup
                        )
                        is PatientAuthState.MustChangePassword -> RequiredPasswordChangeScreen(
                            loading = authLoading,
                            message = authMessage,
                            onSubmit = authViewModel::changePassword,
                            onLogout = authViewModel::logout
                        )
                        is PatientAuthState.Authenticated -> {
                            var showDeveloperMode by rememberSaveable { mutableStateOf(false) }
                            var showConsentSettings by rememberSaveable { mutableStateOf(false) }
                            var showRppgCamera by rememberSaveable { mutableStateOf(false) }
                            var selectedTab by rememberSaveable { mutableStateOf(PatientTab.HOME) }
                            var showNotice by rememberSaveable { mutableStateOf(false) }
                            val noticePolicy = remember {
                                InterventionNoticePolicy(KeystoreNoticeVersionStore(this@MainActivity))
                            }
                            val mobileAuth by MobileAuthRuntime.state.collectAsState()
                            val rppgResult by rppgViewModel.latestResult.collectAsState()
                            val livePpg by viewModel.ppgPoints.collectAsState()
                            LaunchedEffect(Unit) {
                                showNotice = noticePolicy.requiresAcknowledgement()
                            }
                            LaunchedEffect(rppgResult?.jobId) {
                                val result = rppgResult ?: return@LaunchedEffect
                                if (result.status == "completed" && rppgViewModel.markResultRouted(result.jobId)) {
                                    result.toPrediction()?.let(viewModel::handleCameraPrediction)
                                    showRppgCamera = false
                                    selectedTab = PatientTab.CHAT
                                }
                            }
                            LaunchedEffect(forceStateCheckLaunchCounter) {
                                if (forceStateCheckLaunchCounter > 0) showDeveloperMode = false
                            }
                            LaunchedEffect(selectedTab) {
                                when (selectedTab) {
                                    PatientTab.DASHBOARD -> dashboardViewModel.load()
                                    PatientTab.CHAT -> viewModel.openChat()
                                    PatientTab.HOME -> Unit
                                }
                            }
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f)) {
                                    if (showRppgCamera) {
                                        RppgCameraScreen(
                                            viewModel = rppgViewModel,
                                            onClose = { showRppgCamera = false }
                                        )
                                    } else if (selectedTab == PatientTab.DASHBOARD) {
                                        PatientDashboardScreen(
                                            viewModel = dashboardViewModel,
                                            livePpg = livePpg,
                                            onClose = { selectedTab = PatientTab.HOME }
                                        )
                                    } else if (showDeveloperMode) {
                                        HealthMonitorScreen(
                                            viewModel = viewModel,
                                            onSaveCsv = { saveToCsv() },
                                            onExitDeveloper = { showDeveloperMode = false }
                                        )
                                    } else {
                                        UserHomeScreen(
                                            viewModel = viewModel,
                                            rppgViewModel = rppgViewModel,
                                            user = state.user,
                                            onOpenSettings = {
                                                authViewModel.clearMessage()
                                                showConsentSettings = true
                                            },
                                            onLogout = authViewModel::logout,
                                            onDeveloperUnlock = { showDeveloperMode = true },
                                            onOpenRppgCamera = {
                                                if (rppgViewModel.prepareCapture()) showRppgCamera = true
                                            },
                                            showChatTab = selectedTab == PatientTab.CHAT,
                                            onChatClosed = { selectedTab = PatientTab.HOME }
                                        )
                                    }
                                }
                                if (!showRppgCamera && !showDeveloperMode) {
                                    NavigationBar(containerColor = HealthSurface) {
                                        PatientTab.values().forEach { tab ->
                                            NavigationBarItem(
                                                selected = selectedTab == tab,
                                                onClick = { selectedTab = tab },
                                                icon = { Text(if (selectedTab == tab) "●" else "○") },
                                                label = { Text(tab.label) }
                                            )
                                        }
                                    }
                                }
                            }
                            if (showConsentSettings) {
                                PatientConsentSettingsDialog(
                                    current = mobileAuth.consent,
                                    loading = authLoading,
                                    message = authMessage,
                                    onDismiss = {
                                        authViewModel.clearMessage()
                                        showConsentSettings = false
                                    },
                                    onSave = authViewModel::updateConsent
                                )
                            }
                            if (showNotice) {
                                InterventionProductNoticeDialog(
                                    onAcknowledge = {
                                        if (noticePolicy.acknowledge()) showNotice = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureLockScreenLaunch(intent)
        requestCriticalPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        requestNextSpecialPermissionIfNeeded()
    }

    private fun configureLockScreenLaunch(intent: Intent?) {
        val forceStateCheck = intent?.getBooleanExtra(EXTRA_FORCE_STATE_CHECK_LOCK_SCREEN, false) == true
        suppressPermissionPrompts = forceStateCheck
        if (forceStateCheck) {
            forceStateCheckLaunchCounter += 1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            PhoneMonitoringState.latestPrediction.value?.takeIf {
                AlertActionPolicy.resolve(it) == AlertAction.REQUIRED
            } ?: run {
                val prediction = CravingPrediction(
                    cravingClass = 1,
                    timestampMs = System.currentTimeMillis(),
                    rawBody = """{"class":1,"source":"lock_screen_launch"}"""
                )
                PhoneMonitoringState.registerAlertAction(prediction, AlertAction.REQUIRED)
                PhoneMonitoringState.publishPrediction(prediction)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(false)
                setTurnScreenOn(false)
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun requestCriticalPermissionsIfNeeded() {
        if (suppressPermissionPrompts) return
        if (requestNotificationPermissionIfNeeded()) return
        requestNextSpecialPermissionIfNeeded()
    }

    private fun requestPermissionsFromUserAction() {
        suppressPermissionPrompts = false
        notificationPermissionRequestInFlight = false
        getSharedPreferences(PREF_PERMISSIONS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_FULL_SCREEN_PERMISSION_ASKED, false)
            .putBoolean(PREF_BATTERY_OPTIMIZATION_ASKED, false)
            .apply()
        requestCriticalPermissionsIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequestInFlight = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQ)
            return true
        }
        return false
    }

    private fun requestNextSpecialPermissionIfNeeded() {
        if (suppressPermissionPrompts) return
        if (notificationPermissionRequestInFlight) return
        if (requestFullScreenIntentPermissionIfNeeded()) return
        requestBatteryOptimizationExceptionIfNeeded()
    }

    private fun requestFullScreenIntentPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.canUseFullScreenIntent()) return false
        if (wasPermissionPromptShown(PREF_FULL_SCREEN_PERMISSION_ASKED)) return false

        markPermissionPromptShown(PREF_FULL_SCREEN_PERMISSION_ASKED)
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }.onFailure {
            openAppSettings()
        }
        return true
    }

    private fun requestBatteryOptimizationExceptionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return false
        if (wasPermissionPromptShown(PREF_BATTERY_OPTIMIZATION_ASKED)) return false

        markPermissionPromptShown(PREF_BATTERY_OPTIMIZATION_ASKED)
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }.onFailure {
            openAppSettings()
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQ) {
            notificationPermissionRequestInFlight = false
            requestNextSpecialPermissionIfNeeded()
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    private fun wasPermissionPromptShown(key: String): Boolean =
        getSharedPreferences(PREF_PERMISSIONS, MODE_PRIVATE).getBoolean(key, false)

    private fun markPermissionPromptShown(key: String) {
        getSharedPreferences(PREF_PERMISSIONS, MODE_PRIVATE)
            .edit()
            .putBoolean(key, true)
            .apply()
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
                viewModel.allCravingClassRaw.forEach { (ts, v) -> w.write("CRAVING_STAGE,$ts,$v\n") }
                viewModel.uploadLatencySnapshot().forEach { (ts, v) -> w.write("POST_LATENCY_MS,$ts,$v\n") }
                viewModel.predictionLatencySnapshot().forEach { (ts, v) -> w.write("PREDICTION_LATENCY_MS,$ts,$v\n") }
                viewModel.allStateCheckResults.forEach { result ->
                    w.write("STATE_CHECK_TOTAL,${result.timestampMs},${result.totalScore}\n")
                    w.write("STATE_CHECK_MEAN,${result.timestampMs},${result.meanScore}\n")
                    w.write("STATE_CHECK_RAW_TOTAL,${result.timestampMs},${result.rawTotalScore}\n")
                    w.write("STATE_CHECK_RAW_MEAN,${result.timestampMs},${result.rawMeanScore}\n")
                    result.scoredItems.forEachIndexed { index, value ->
                        w.write("STATE_CHECK_ITEM_${index + 1},${result.timestampMs},$value\n")
                    }
                    result.responses.forEachIndexed { index, value ->
                        w.write("STATE_CHECK_RAW_ITEM_${index + 1},${result.timestampMs},$value\n")
                    }
                }
            }
            Toast.makeText(this, "저장: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_FORCE_STATE_CHECK_LOCK_SCREEN = "com.example.healthsensor.EXTRA_FORCE_STATE_CHECK_LOCK_SCREEN"
        private const val NOTIFICATION_PERMISSION_REQ = 200
        private const val PREF_PERMISSIONS = "permission_prompts"
        private const val PREF_FULL_SCREEN_PERMISSION_ASKED = "full_screen_permission_asked"
        private const val PREF_BATTERY_OPTIMIZATION_ASKED = "battery_optimization_asked"
    }
}

@Composable
fun UserHomeScreen(
    viewModel: SensorViewModel,
    rppgViewModel: RppgViewModel,
    user: AuthUser,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    onDeveloperUnlock: () -> Unit,
    onOpenRppgCamera: () -> Unit,
    showChatTab: Boolean,
    onChatClosed: () -> Unit
) {
    val watchConnectionState by viewModel.watchConnectionState.collectAsState()
    val latestPrediction by viewModel.latestPrediction.collectAsState()
    val isStateCheckRequired by viewModel.isStateCheckRequired.collectAsState()
    val stateCheckResponses by viewModel.stateCheckResponses.collectAsState()
    val isTalkChoiceRequired by viewModel.isTalkChoiceRequired.collectAsState()
    val isAuqChoiceRequired by viewModel.isAuqChoiceRequired.collectAsState()
    val isChatVisible by viewModel.isChatVisible.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val chatStatus by viewModel.chatStatus.collectAsState()
    val isChatSending by viewModel.isChatSending.collectAsState()
    val pendingChatRetry by viewModel.pendingChatRetry.collectAsState()
    val conversationPhase by viewModel.conversationPhase.collectAsState()
    val sessionReportStatus by viewModel.sessionReportStatus.collectAsState()
    val inactivityTimeoutSeconds by viewModel.sessionInactivityTimeoutSeconds.collectAsState()
    val isVoiceRecording by viewModel.isVoiceRecording.collectAsState()
    val isVoiceTranscribing by viewModel.isVoiceTranscribing.collectAsState()
    val voiceDraft by viewModel.voiceDraft.collectAsState()
    val voiceStatus by viewModel.voiceStatus.collectAsState()
    val cravingColor = cravingProbabilityTone(latestPrediction?.cravingProbability)
    val rppgStatus by rppgViewModel.serviceStatus.collectAsState()
    val rppgResult by rppgViewModel.latestResult.collectAsState()
    val canCaptureRppg = MobileAuthRuntime.state.collectAsState().value.canCaptureRppg

    when {
        isTalkChoiceRequired -> TalkChoiceScreen(
            onTalkNow = viewModel::chooseTalkNow,
            onLater = {
                viewModel.chooseTalkLater()
                onChatClosed()
            }
        )
        isAuqChoiceRequired -> OptionalAuqChoiceScreen(
            onComplete = viewModel::chooseAuqForm,
            onSkip = viewModel::skipAuqAndTalk
        )
        isStateCheckRequired -> StateCheckScreen(
            questions = viewModel.stateCheckQuestions,
            responses = stateCheckResponses,
            onResponse = viewModel::updateStateCheckResponse,
            onSubmit = viewModel::submitStateCheckResponses
        )
        isChatVisible && showChatTab -> CravingChatScreen(
            messages = chatMessages,
            chatStatus = chatStatus,
            isChatSending = isChatSending,
            pendingRetry = pendingChatRetry,
            phase = conversationPhase,
            reportStatus = sessionReportStatus,
            inactivityTimeoutSeconds = inactivityTimeoutSeconds,
            isVoiceRecording = isVoiceRecording,
            isVoiceTranscribing = isVoiceTranscribing,
            voiceDraft = voiceDraft,
            voiceStatus = voiceStatus,
            onSend = viewModel::sendChatMessage,
            onStartRecording = viewModel::startVoiceRecording,
            onStopRecording = viewModel::stopVoiceRecording,
            onVoiceDraftConsumed = viewModel::consumeVoiceDraft,
            onRetry = viewModel::retryChatMessage,
            onFinish = {
                viewModel.finishConversationManually()
                onChatClosed()
            },
            onClose = {
                viewModel.closeChat()
                onChatClosed()
            }
        )
        else -> UserDashboardScreen(
            user = user,
            watchConnectionState = watchConnectionState,
            cravingProbability = latestPrediction?.cravingProbability,
            predictionSource = latestPrediction?.source,
            predictionTimestampMs = latestPrediction?.timestampMs,
            cravingColor = cravingColor,
            rppgStatus = rppgStatus,
            rppgResult = rppgResult,
            canCaptureRppg = canCaptureRppg,
            onOpenSettings = onOpenSettings,
            onLogout = onLogout,
            onOpenRppgCamera = onOpenRppgCamera,
            onDeveloperUnlock = onDeveloperUnlock
        )
    }
}

@Composable
private fun UserDashboardScreen(
    user: AuthUser,
    watchConnectionState: WatchConnectionState,
    cravingProbability: Float?,
    predictionSource: String?,
    predictionTimestampMs: Long?,
    cravingColor: Color,
    rppgStatus: RppgServiceStatus?,
    rppgResult: RppgJobResult?,
    canCaptureRppg: Boolean,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    onOpenRppgCamera: () -> Unit,
    onDeveloperUnlock: () -> Unit
) {
    val rppgPresentation = WatchRppgPresentationPolicy.resolve(
        watchState = watchConnectionState,
        canCaptureRppg = canCaptureRppg,
        serviceStatus = rppgStatus
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HealthBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onDeveloperUnlock) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val releasedBeforeTimeout = withTimeoutOrNull(DEVELOPER_HOLD_MS) {
                            waitForUpOrCancellation()
                        }
                        if (releasedBeforeTimeout == null) {
                            onDeveloperUnlock()
                            waitForUpOrCancellation()
                        }
                    }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("NeuroTruth 사용자", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = HealthText)
                Text(user.email, fontSize = 13.sp, color = HealthMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onOpenSettings) { Text("설정") }
                TextButton(onClick = onLogout) { Text("로그아웃", fontSize = 12.sp) }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = HealthSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, HealthLine)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .background(cravingColor.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = cravingProbabilityBadge(cravingProbability),
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                        color = cravingColor
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("갈망 가능성", fontSize = 12.sp, color = HealthMuted, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = cravingProbabilityLabel(cravingProbability),
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                        color = HealthText
                    )
                    Text(
                        text = cravingProbabilityMessage(cravingProbability),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = HealthMuted
                    )
                    Text(
                        text = predictionMetaLabel(predictionSource, predictionTimestampMs),
                        fontSize = 12.sp,
                        color = HealthMuted
                    )
                    Text(
                        text = rppgPresentation.guidance,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = HealthMuted
                    )
                    if (rppgPresentation.primaryAction) {
                        Button(
                            onClick = onOpenRppgCamera,
                            enabled = rppgPresentation.actionEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(rppgPresentation.actionLabel, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onOpenRppgCamera,
                            enabled = rppgPresentation.actionEnabled
                        ) {
                            Text(rppgPresentation.actionLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rppgResult?.let { RppgResultCard(it) }
            UserStatusTile(
                label = "Watch 연결",
                value = rppgPresentation.watchLabel,
                color = when (watchConnectionState) {
                    WatchConnectionState.CONNECTED -> HealthPrimary
                    WatchConnectionState.ERROR -> HealthWarning
                    else -> HealthIdle
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CravingNoticeCard(title: String, message: String, color: Color) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
            Text(message, fontSize = 12.sp, lineHeight = 17.sp, color = HealthText)
        }
    }
}

@Composable
private fun UserStatusTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = HealthSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, HealthLine)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                Text(label, fontSize = 12.sp, color = HealthMuted, fontWeight = FontWeight.SemiBold)
            }
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HealthText)
        }
    }
}

@Composable
private fun TalkChoiceScreen(onTalkNow: () -> Unit, onLater: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = HealthSurface)) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("잠시 상태를 확인해 볼까요?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "센서 변화가 갈망과 관련된 신호일 가능성이 있습니다. 확정된 판단은 아니며, 원할 때 대화를 시작할 수 있습니다.",
                    color = HealthMuted,
                    lineHeight = 20.sp
                )
                Button(onClick = onTalkNow, modifier = Modifier.fillMaxWidth()) { Text("지금 대화하기") }
                OutlinedButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text("나중에") }
            }
        }
    }
}

@Composable
private fun OptionalAuqChoiceScreen(onComplete: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = HealthSurface)) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("간단한 설문은 선택 사항입니다", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("작성하지 않아도 대화와 중재 기능을 동일하게 이용할 수 있습니다.", color = HealthMuted)
                Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text("AUQ 작성하기") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("건너뛰고 대화하기") }
            }
        }
    }
}

@Composable
private fun InterventionProductNoticeDialog(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("NeuroTruth 사용 안내") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("NeuroTruth는 연구 참여 환자가 센서 기록과 대화를 통해 자신의 상태를 돌아보고, 필요한 순간에 대화형 중재를 받도록 돕는 연구용 서비스입니다.")
                Text("동의한 범위에서 센서·예측·설문·대화 기록이 계속 저장될 수 있으며 연구 분석에 사용될 수 있습니다.")
                Text("이 서비스는 의료 진단, 치료 또는 응급 대응을 대신하지 않습니다. 즉각적인 위험이나 응급 상황에는 119 또는 이용 가능한 긴급 지원 기관에 연락하세요.")
            }
        },
        confirmButton = { Button(onClick = onAcknowledge) { Text("확인했어요") } }
    )
}

@Composable
private fun StateCheckScreen(
    questions: List<StateCheckQuestion>,
    responses: Map<Int, Int>,
    onResponse: (Int, Int) -> Unit,
    onSubmit: () -> Unit
) {
    val answered = responses.size
    val canSubmit = answered == questions.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HealthBackground)
            .padding(horizontal = 18.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = HealthDangerSoft),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, HealthDanger.copy(alpha = 0.22f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("상태 확인", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HealthText)
                Text("지금 상태를 짧게 확인해 주세요.", fontSize = 13.sp, color = HealthMuted)
                LinearProgressIndicator(
                    progress = { answered / questions.size.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = HealthDanger,
                    trackColor = Color.White
                )
                Text("$answered / ${questions.size} 응답", fontSize = 12.sp, color = HealthMuted)
            }
        }

        questions.forEach { question ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HealthSurface, RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${question.number}. ${question.text}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HealthText
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AUQ_RESPONSE_LABELS.forEachIndexed { index, label ->
                        val value = index
                        val selected = responses[question.number] == value
                        OutlinedButton(
                            onClick = { onResponse(question.number, value) },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) HealthDanger else HealthSurface,
                                contentColor = if (selected) Color.White else HealthMuted
                            ),
                            border = BorderStroke(1.dp, if (selected) HealthDanger else HealthLine)
                        ) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Button(
            onClick = onSubmit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HealthDanger)
        ) {
            Text("결과 저장", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CravingChatScreen(
    messages: List<ChatMessage>,
    chatStatus: String,
    isChatSending: Boolean,
    pendingRetry: PendingChatRetry?,
    phase: String,
    reportStatus: String,
    inactivityTimeoutSeconds: Int?,
    isVoiceRecording: Boolean,
    isVoiceTranscribing: Boolean,
    voiceDraft: String?,
    voiceStatus: String,
    onSend: (String, String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onVoiceDraftConsumed: () -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
    onClose: () -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var inputModality by rememberSaveable { mutableStateOf("text") }
    var autoRead by rememberSaveable { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var speakingMessageIndex by remember { mutableStateOf<Int?>(null) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    val context = LocalContext.current
    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onStartRecording() }

    DisposableEffect(context) {
        val engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = engineLanguageAvailable(ttsEngine)
            }
        }
        ttsEngine = engine
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                Handler(Looper.getMainLooper()).post { speakingMessageIndex = null }
            }
            override fun onDone(utteranceId: String?) {
                Handler(Looper.getMainLooper()).post { speakingMessageIndex = null }
            }
        })
        onDispose {
            engine.stop()
            engine.shutdown()
            ttsEngine = null
        }
    }

    fun speak(index: Int, text: String) {
        val engine = ttsEngine ?: return
        if (!ttsReady) return
        engine.stop()
        speakingMessageIndex = index
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-$index")
    }

    LaunchedEffect(voiceDraft) {
        voiceDraft?.let {
            draft = it
            inputModality = "voice"
            onVoiceDraftConsumed()
        }
    }
    LaunchedEffect(messages.size, autoRead, ttsReady) {
        val index = messages.lastIndex
        val latest = messages.getOrNull(index)
        if (autoRead && ttsReady && latest?.sender == ChatSender.BOT) speak(index, latest.text)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HealthBackground)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("대화", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = HealthText)
                Text(
                    when (phase) {
                        "free_dialogue" -> "자유 대화"
                        "safety_check" -> "이전 대화 · 안전 확인"
                        "intervention_dialogue" -> "이전 대화 · 대화형 중재"
                        "closing" -> "마무리"
                        "completed" -> "종료됨"
                        else -> "대화 진행 중"
                    },
                    fontSize = 12.sp,
                    color = HealthMuted
                )
                Text(
                    chatStatus,
                    fontSize = 11.sp,
                    color = if (chatStatus.contains("실패")) HealthDanger else HealthMuted
                )
            }
            TextButton(onClick = onClose) {
                Text("홈", color = HealthMuted)
            }
        }

        Text(
            buildString {
                append("관리자가 설정한 ")
                append(inactivityTimeoutSeconds?.let(::timeoutDurationLabel) ?: "비활동 시간")
                append(" 동안 응답이 없으면 세션이 자동 종료됩니다")
                append(" · 보고서 상태: ${reportStatus.ifBlank { "대기" }}")
            },
            fontSize = 11.sp,
            color = HealthMuted
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            messages.forEachIndexed { index, message ->
                ChatBubble(
                    message = message,
                    isSpeaking = speakingMessageIndex == index,
                    onListen = if (message.sender == ChatSender.BOT && ttsReady) {
                        {
                            if (speakingMessageIndex == index) {
                                ttsEngine?.stop()
                                speakingMessageIndex = null
                            } else {
                                speak(index, message.text)
                            }
                        }
                    } else null
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AI 응답 자동 읽기", fontSize = 12.sp, color = HealthMuted)
            Switch(checked = autoRead, onCheckedChange = { autoRead = it }, enabled = ttsReady)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(8.dp),
                placeholder = { Text("현재 상태 입력") }
            )
            OutlinedButton(
                onClick = {
                    if (isVoiceRecording) {
                        onStopRecording()
                    } else if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        onStartRecording()
                    } else {
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !isVoiceTranscribing && !isChatSending,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Text(if (isVoiceRecording) "정지" else if (isVoiceTranscribing) "인식 중" else "마이크")
            }
            Button(
                onClick = {
                    onSend(draft, inputModality)
                    draft = ""
                    inputModality = "text"
                },
                enabled = draft.isNotBlank() && !isChatSending,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isChatSending) "대기" else "전송")
            }
        }

        if (voiceStatus.isNotBlank()) {
            Text(voiceStatus, fontSize = 11.sp, color = HealthMuted)
        }

        if (pendingRetry != null) {
            OutlinedButton(
                onClick = onRetry,
                enabled = !isChatSending && pendingRetry.attemptsRemaining > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("다시 시도")
            }
        }

        TextButton(
            onClick = onFinish,
            enabled = !isChatSending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("대화 종료", color = HealthDanger, fontWeight = FontWeight.Bold)
        }
    }
}

private fun timeoutDurationLabel(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600}시간"
    seconds % 60 == 0 -> "${seconds / 60}분"
    else -> "${seconds}초"
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    isSpeaking: Boolean,
    onListen: (() -> Unit)?
) {
    val isUser = message.sender == ChatSender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .background(
                    if (isUser) HealthPrimary else HealthSurface,
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                message.text,
                fontSize = 14.sp,
                color = if (isUser) Color.White else HealthText
            )
            if (onListen != null) {
                TextButton(onClick = onListen, contentPadding = PaddingValues(0.dp)) {
                    Text(if (isSpeaking) "정지" else "듣기", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DeveloperHoldButton(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEAF0F4), RoundedCornerShape(8.dp))
            .pointerInput(onUnlock) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val releasedBeforeTimeout = withTimeoutOrNull(DEVELOPER_HOLD_MS) {
                        waitForUpOrCancellation()
                    }
                    if (releasedBeforeTimeout == null) {
                        onUnlock()
                        waitForUpOrCancellation()
                    }
                }
            }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("관리", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HealthMuted)
    }
}

internal val AUQ_RESPONSE_LABELS = listOf(
    "매우 그렇지 않다",
    "그렇지 않다",
    "조금 그렇지 않다",
    "보통이다",
    "조금 그렇다",
    "그렇다",
    "매우 그렇다"
)

internal fun cravingProbabilityLabel(probability: Float?): String = when {
    probability == null || !probability.isFinite() -> "측정 대기"
    probability < 0.25f -> "안전"
    probability < 0.50f -> "관찰"
    probability < 0.75f -> "주의"
    else -> "심각"
}

private fun cravingProbabilityBadge(probability: Float?): String = when (cravingProbabilityLabel(probability)) {
    "안전" -> "안전"
    "관찰" -> "관찰"
    "주의" -> "주의"
    "심각" -> "심각"
    else -> "--"
}

private fun cravingProbabilityMessage(probability: Float?): String = when {
    probability == null || !probability.isFinite() -> "측정 결과를 기다리는 중이에요."
    probability < 0.25f -> "아무 문제 없어요!"
    probability < 0.50f -> "관찰이 필요해요, 심각하진 않아요!"
    probability < 0.75f -> "주의가 필요해요, 술이 드시고 싶으신가요?"
    else -> "갈망이 심해보여요. 챗봇과 대화를 시작할까요?"
}

private fun cravingProbabilityTone(probability: Float?): Color = when {
    probability == null || !probability.isFinite() -> HealthIdle
    probability < 0.25f -> HealthPrimary
    probability < 0.50f -> HealthAccent
    probability < 0.75f -> HealthWarning
    else -> HealthDanger
}

private fun predictionMetaLabel(source: String?, timestampMs: Long?): String {
    if (timestampMs == null || timestampMs <= 0L) return "측정 출처와 시각을 기다리는 중이에요."
    val sourceLabel = if (source == "camera_rppg") "카메라" else "Watch"
    val measuredAt = SimpleDateFormat("M월 d일 HH:mm:ss", Locale.KOREA).format(Date(timestampMs))
    return "$sourceLabel · $measuredAt"
}

private fun cravingClassLabel(cravingClass: Int?): String = when (cravingClass) {
    0 -> "낮음"
    1 -> "높음"
    else -> "대기"
}

private fun engineLanguageAvailable(engine: TextToSpeech?): Boolean {
    val result = engine?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
    return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
}

private const val DEVELOPER_HOLD_MS = 2_500L

@Composable
fun HealthMonitorScreen(
    viewModel: SensorViewModel,
    onSaveCsv: () -> Unit,
    onExitDeveloper: () -> Unit = {}
) {
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
    val uploadLatencyPoints by viewModel.uploadLatencyPoints.collectAsState()
    val predictionLatencyPoints by viewModel.predictionLatencyPoints.collectAsState()
    val uploadLatencyStatus by viewModel.uploadLatencyStatus.collectAsState()
    val predictionLatencyStatus by viewModel.predictionLatencyStatus.collectAsState()
    val latestPrediction by viewModel.latestPrediction.collectAsState()
    val cravingClassPoints by viewModel.cravingClassPoints.collectAsState()
    val isCravingSimulationRunning by viewModel.isCravingSimulationRunning.collectAsState()
    val simulationStatus by viewModel.simulationStatus.collectAsState()
    val chatReadTimeoutMinutes by viewModel.chatReadTimeoutMinutes.collectAsState()
    val chatTimeoutSettingStatus by viewModel.chatTimeoutSettingStatus.collectAsState()
    var chatTimeoutDraft by rememberSaveable(chatReadTimeoutMinutes) {
        mutableStateOf(chatReadTimeoutMinutes.toString())
    }
    val latestHr = hrPoints.lastOrNull()?.value?.let { formatLatestValue(it, "bpm") } ?: "--"
    val latestTemp = skinTempPoints.lastOrNull()?.value?.let { formatLatestValue(it, "°C") } ?: "--"
    val latestUploadLatency = uploadLatencyPoints.lastOrNull()?.value?.let { "%.0f ms".format(it) } ?: "--"
    val latestPredictionLatency = predictionLatencyPoints.lastOrNull()?.value?.let { "%.0f ms".format(it) } ?: "--"
    val cravingClass = latestPrediction?.cravingClass
    val cravingColor = cravingTone(cravingClass)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HealthBackground)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Health Monitor", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = HealthText)
                Text("Galaxy Watch sensor stream", fontSize = 12.sp, color = HealthMuted)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onExitDeveloper,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("사용자 화면", fontSize = 12.sp, color = HealthMuted)
                }
                StatusPill(
                    text = if (isReceiving) "LIVE" else "WAIT",
                    color = if (isReceiving) HealthPrimary else HealthIdle
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "심박",
                value = latestHr,
                detail = if (isReceiving) "watch live" else "수신 대기",
                accentColor = HealthPrimary,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "갈망",
                value = cravingClassLabel(cravingClass),
                detail = "server prediction",
                accentColor = cravingColor,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "전송",
                value = if (isUploadEnabled) "ON" else "OFF",
                detail = "20s window / 10s",
                accentColor = if (isUploadEnabled) HealthPrimary else HealthIdle,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "피부온도",
                value = latestTemp,
                detail = "skin temp",
                accentColor = HealthAccent,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "POST 지연",
                value = latestUploadLatency,
                detail = "request round-trip",
                accentColor = Color(0xFF0087D4),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "예측 지연",
                value = latestPredictionLatency,
                detail = "server event receive",
                accentColor = Color(0xFF7C4DFF),
                modifier = Modifier.weight(1f)
            )
        }

        ConnectionPanel(
            title = "서버 전송",
            status = uploadStatus,
            url = serverUrl,
            onUrlChange = viewModel::updateServerUrl,
            textFieldLabel = "POST URL",
            placeholder = "http://192.168.0.10:8000/api/sensor-windows",
            isEnabled = isUploadEnabled,
            onToggle = { viewModel.setUploadEnabled(!isUploadEnabled) },
            activeText = "전송 중지",
            idleText = "전송 시작",
            accentColor = HealthPrimary
        )

        ConnectionPanel(
            title = "예측 수신",
            status = predictionStatus,
            url = predictionUrl,
            onUrlChange = viewModel::updatePredictionUrl,
            textFieldLabel = "예측 수신 URL",
            placeholder = "http://192.168.0.10:8000/api/predictions/stream",
            isEnabled = isPredictionReceiverEnabled,
            onToggle = { viewModel.setPredictionReceiverEnabled(!isPredictionReceiverEnabled) },
            activeText = "수신 중지",
            idleText = "수신 시작",
            accentColor = cravingColor
        )

        SimulationPanel(
            isRunning = isCravingSimulationRunning,
            status = simulationStatus,
            onToggle = {
                if (isCravingSimulationRunning) {
                    viewModel.stopCravingSimulation()
                } else {
                    viewModel.startCravingSimulation()
                }
            }
        )

        SectionTitle("통신 레이턴시")
        Text(uploadLatencyStatus, fontSize = 11.sp, color = HealthMuted)
        SensorChartCard(
            title = "POST 왕복 지연",
            unit = "ms",
            lineColor = Color(0xFF0087D4),
            points = uploadLatencyPoints,
            entries = viewModel.toMpEntries(uploadLatencyPoints),
            fixedYMin = 0f
        )
        Text(predictionLatencyStatus, fontSize = 11.sp, color = HealthMuted)
        SensorChartCard(
            title = "예측 수신 지연",
            unit = "ms",
            lineColor = Color(0xFF7C4DFF),
            points = predictionLatencyPoints,
            entries = viewModel.toMpEntries(predictionLatencyPoints),
            fixedYMin = 0f
        )

        SectionTitle("예측 상태")

        // 서버에서 돌아온 상태 단계는 센서와 같은 시간축에 step chart로 표시한다.
        SensorChartCard(
            title = "상태 단계",
            unit = "",
            lineColor = cravingColor,
            points = cravingClassPoints,
            entries = viewModel.toMpEntries(cravingClassPoints),
            fixedYMin = -0.1f,
            fixedYMax = 2.1f,
            lineMode = LineDataSet.Mode.STEPPED
        )

        SectionTitle("Sensor Streams")

        SensorChartCard(title = "심박수 (HR)", unit = "bpm", lineColor = HealthPrimary, points = hrPoints, entries = viewModel.toMpEntries(hrPoints))
        SensorChartCard(title = "PPG Raw (Green)", unit = "raw", lineColor = Color(0xFF1D9A8A), points = ppgPoints, entries = viewModel.toMpEntries(ppgPoints))
        SensorChartCard(title = "PPG Raw (IR)", unit = "raw", lineColor = Color(0xFF4267D9), points = ppgIrPoints, entries = viewModel.toMpEntries(ppgIrPoints))
        SensorChartCard(title = "PPG Raw (Red)", unit = "raw", lineColor = HealthDanger, points = ppgRedPoints, entries = viewModel.toMpEntries(ppgRedPoints))
        SensorChartCard(title = "EDA (피부전도도)", unit = "μS", lineColor = HealthWarning, points = edaPoints, entries = viewModel.toMpEntries(edaPoints))
        SensorChartCard(title = "가속도 X축", unit = "m/s²", lineColor = Color(0xFF7C4DFF), points = accelXPoints, entries = viewModel.toMpEntries(accelXPoints))
        SensorChartCard(title = "가속도 Y축", unit = "m/s²", lineColor = Color(0xFF0087D4), points = accelYPoints, entries = viewModel.toMpEntries(accelYPoints))
        SensorChartCard(title = "가속도 Z축", unit = "m/s²", lineColor = Color(0xFF5C6BC0), points = accelZPoints, entries = viewModel.toMpEntries(accelZPoints))
        SensorChartCard(title = "피부 온도 (SkinTemp)", unit = "°C", lineColor = HealthAccent, points = skinTempPoints, entries = viewModel.toMpEntries(skinTempPoints))

        SectionTitle("상담 관리")
        OutlinedTextField(
            value = chatTimeoutDraft,
            onValueChange = { chatTimeoutDraft = it.filter(Char::isDigit).take(4) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("채팅 응답 제한시간(분)") },
            supportingText = { Text("허용 범위 1~1,440분 · 현재 ${chatReadTimeoutMinutes}분") }
        )
        Button(
            onClick = {
                val minutes = chatTimeoutDraft.toIntOrNull()
                if (minutes != null) viewModel.applyChatReadTimeoutMinutes(minutes)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = chatTimeoutDraft.toIntOrNull() != null,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HealthAccent)
        ) {
            Text("제한시간 적용")
        }
        if (chatTimeoutSettingStatus.isNotBlank()) {
            Text(
                chatTimeoutSettingStatus,
                fontSize = 11.sp,
                color = if (chatTimeoutSettingStatus.contains("입력")) HealthDanger else HealthMuted
            )
        }
        OutlinedButton(
            onClick = viewModel::resetInterventionState,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, HealthWarning),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = HealthWarning)
        ) {
            Text("상담 상태 초기화")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { viewModel.clearAll() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, HealthLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HealthText)
            ) {
                Text("초기화")
            }
            Button(
                onClick = onSaveCsv,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HealthPrimary)
            ) {
                Text("CSV 저장")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    detail: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = HealthSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, HealthLine)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(modifier = Modifier.size(8.dp).background(accentColor, CircleShape))
                Text(label, fontSize = 12.sp, color = HealthMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HealthText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 11.sp, color = HealthMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ConnectionPanel(
    title: String,
    status: String,
    url: String,
    onUrlChange: (String) -> Unit,
    textFieldLabel: String,
    placeholder: String,
    isEnabled: Boolean,
    onToggle: () -> Unit,
    activeText: String,
    idleText: String,
    accentColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = HealthSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, HealthLine)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HealthText)
                StatusPill(
                    text = if (isEnabled) "ON" else "OFF",
                    color = if (isEnabled) accentColor else HealthIdle
                )
            }
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(textFieldLabel) },
                placeholder = { Text(placeholder) },
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = HealthLine,
                    focusedLabelColor = accentColor,
                    cursorColor = accentColor
                )
            )
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isEnabled) HealthDanger else accentColor,
                    contentColor = Color.White
                )
            ) {
                Text(if (isEnabled) activeText else idleText, fontWeight = FontWeight.SemiBold)
            }
            Text(status, fontSize = 11.sp, color = HealthMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SimulationPanel(
    isRunning: Boolean,
    status: String,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = HealthSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, HealthLine)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("테스트 시뮬레이션", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HealthText)
            Text("버튼을 누르면 낮음, 주의, 확인 필요 단계가 10초 간격으로 들어온 것처럼 처리됩니다.", fontSize = 12.sp, color = HealthMuted)
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) HealthDanger else HealthWarning,
                    contentColor = Color.White
                )
            ) {
                Text(if (isRunning) "테스트 중지" else "단계 테스트 시작", fontWeight = FontWeight.SemiBold)
            }
            Text(status, fontSize = 11.sp, color = HealthMuted)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = HealthMuted,
        modifier = Modifier.padding(top = 2.dp)
    )
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
    val uploadLatencyPoints by viewModel.uploadLatencyPoints.collectAsState()
    val predictionLatencyPoints by viewModel.predictionLatencyPoints.collectAsState()
    val uploadLatencyStatus by viewModel.uploadLatencyStatus.collectAsState()
    val predictionLatencyStatus by viewModel.predictionLatencyStatus.collectAsState()
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
                    placeholder = { Text("http://192.168.0.10:8000/api/sensor-windows") }
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
                        text = if (isUploadEnabled) "20초 윈도우 · 10초 주기" else "대기",
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
                    label = { Text("예측 수신 URL") },
                    placeholder = { Text("http://192.168.0.10:8000/api/predictions/stream") }
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
                        text = latestPrediction?.let { cravingClassLabel(it.cravingClass) } ?: "대기",
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

        Text("통신 레이턴시", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(uploadLatencyStatus, fontSize = 11.sp, color = Color.Gray)
        SensorChartCard(
            title = "POST 왕복 지연",
            unit = "ms",
            lineColor = Color(0xFF0087D4),
            points = uploadLatencyPoints,
            entries = viewModel.toMpEntries(uploadLatencyPoints),
            fixedYMin = 0f
        )
        Text(predictionLatencyStatus, fontSize = 11.sp, color = Color.Gray)
        SensorChartCard(
            title = "예측 수신 지연",
            unit = "ms",
            lineColor = Color(0xFF7C4DFF),
            points = predictionLatencyPoints,
            entries = viewModel.toMpEntries(predictionLatencyPoints),
            fixedYMin = 0f
        )

        // 서버에서 돌아온 상태 단계는 센서와 같은 시간축에 step chart로 표시한다.
        SensorChartCard(
            title = "상태 단계",
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
    // 상태 단계처럼 의미 범위가 고정된 경우에만 y축을 고정한다.
    fixedYMin: Float? = null,
    fixedYMax: Float? = null,
    lineMode: LineDataSet.Mode = LineDataSet.Mode.LINEAR
) {
    val latestValue = points.lastOrNull()?.value
    val colorArgb = lineColor.toArgb()
    val visibleWindowUnits = 100f   // SensorViewModel x축 1칸 = 100ms, 100칸 = 10초
    val axisScaleState = remember { AxisScaleState() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = HealthSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, HealthLine)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HealthText)
                latestValue?.let {
                    Box(
                        modifier = Modifier
                            .background(lineColor.copy(alpha = 0.10f), RoundedCornerShape(50))
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(formatLatestValue(it, unit), fontSize = 12.sp, color = lineColor, fontWeight = FontWeight.Bold)
                    }
                } ?: Text("--", fontSize = 13.sp, color = HealthMuted)
            }

            Spacer(Modifier.height(8.dp))

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
                            textColor = android.graphics.Color.rgb(100, 114, 125)
                            axisLineColor = android.graphics.Color.TRANSPARENT
                            setDrawGridLines(true)
                            gridColor = android.graphics.Color.rgb(232, 238, 243)
                        }
                        setViewPortOffsets(42f, 8f, 12f, 24f)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setNoDataText("워치에서 측정을 시작하세요")
                        setNoDataTextColor(android.graphics.Color.rgb(100, 114, 125))
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
                                lineWidth = 2.0f
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
                            fixedYMin?.let { chart.axisLeft.axisMinimum = it }
                            fixedYMax?.let { chart.axisLeft.axisMaximum = it }
                        }
                        chart.moveViewToX(latestX)
                        dataSet.notifyDataSetChanged()
                        chart.data.notifyDataChanged()
                        chart.notifyDataSetChanged()
                        chart.invalidate()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(156.dp)
            )

            Text("${points.size} points", fontSize = 10.sp, color = HealthMuted, modifier = Modifier.align(Alignment.End))
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

private fun cravingTone(cravingClass: Int?): Color =
    when (cravingClass) {
        0 -> HealthPrimary
        1 -> HealthWarning
        2 -> HealthDanger
        else -> HealthIdle
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
