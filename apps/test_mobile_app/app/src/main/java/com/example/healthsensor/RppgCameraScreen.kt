package com.example.healthsensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal const val RPPG_CAPTURE_SECONDS = 20

@Composable
fun RppgCameraScreen(
    viewModel: RppgViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val phase by viewModel.phase.collectAsState()
    val message by viewModel.message.collectAsState()
    val stability by viewModel.stabilityProgress.collectAsState()
    val result by viewModel.latestResult.collectAsState()
    val hasLocalVideo by viewModel.hasLocalVideo.collectAsState()
    val clientCaptureId by viewModel.clientCaptureId.collectAsState()
    val currentPhase by rememberUpdatedState(phase)
    val currentHasLocalVideo by rememberUpdatedState(hasLocalVideo)
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var countdown by remember { mutableIntStateOf(RPPG_CAPTURE_SECONDS) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val controller = remember {
        RppgCameraController(
            context = context,
            onStability = viewModel::updateStability,
            onRecordingStarted = viewModel::recordingStarted,
            onRecordingFinished = viewModel::recordingFinished,
            onRecordingCancelled = viewModel::recordingCancelled
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (!granted) {
            viewModel.recordingCancelled("카메라 권한이 필요합니다")
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(cameraGranted) {
        if (cameraGranted) controller.bind(lifecycleOwner, previewView)
    }
    LaunchedEffect(clientCaptureId) {
        controller.resetTracker()
    }
    LaunchedEffect(phase) {
        if (phase == RppgCapturePhase.RECORDING) {
            countdown = RPPG_CAPTURE_SECONDS
            repeat(RPPG_CAPTURE_SECONDS) {
                kotlinx.coroutines.delay(1_000L)
                countdown = (countdown - 1).coerceAtLeast(0)
            }
        }
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && currentPhase in PRE_ACCEPTED_SCREEN_PHASES) {
                controller.cancelRecording()
                viewModel.recordingCancelled("앱이 백그라운드로 이동해 촬영을 취소했습니다")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.close()
            if (currentPhase in PRE_ACCEPTED_SCREEN_PHASES ||
                (currentPhase == RppgCapturePhase.FAILED && currentHasLocalVideo)
            ) {
                viewModel.cancelBeforeAccepted()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF101417)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (cameraGranted && phase in CAPTURE_PHASES) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(390.dp)
                        .border(3.dp, if (phase == RppgCapturePhase.RECORDING) Color.Red else Color.White, RoundedCornerShape(48.dp))
                )
            } else if (phase == RppgCapturePhase.ANALYZING || phase == RppgCapturePhase.UPLOADING) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xEEFFFFFF)),
            border = BorderStroke(1.dp, Color(0x22000000))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(phaseLabel(phase), style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (phase == RppgCapturePhase.STABILIZING) {
                    LinearProgressIndicator(progress = { stability }, modifier = Modifier.fillMaxWidth())
                }
                if (phase == RppgCapturePhase.RECORDING) {
                    Text("남은 시간 ${countdown}초", style = MaterialTheme.typography.headlineMedium)
                }
                if (phase == RppgCapturePhase.COMPLETED && result != null) {
                    RppgCompactResult(result!!)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        phase == RppgCapturePhase.FAILED && hasLocalVideo -> Button(
                            onClick = viewModel::retryUpload,
                            modifier = Modifier.weight(1f)
                        ) { Text("업로드 다시 시도") }
                        phase == RppgCapturePhase.FAILED && result?.retryAllowed == true -> Button(
                            onClick = viewModel::retryAnalysis,
                            modifier = Modifier.weight(1f)
                        ) { Text("분석 다시 시도") }
                        phase in setOf(RppgCapturePhase.RETRY_REQUIRED, RppgCapturePhase.FAILED, RppgCapturePhase.COMPLETED) -> Button(
                            onClick = {
                                if (viewModel.prepareCapture()) controller.resetTracker()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("새로 측정") }
                    }
                    OutlinedButton(
                        onClick = {
                            if (phase in PRE_ACCEPTED_SCREEN_PHASES || hasLocalVideo) {
                                controller.cancelRecording()
                                viewModel.cancelBeforeAccepted()
                            }
                            onClose()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (phase in CAPTURE_PHASES) "취소" else "닫기") }
                }
            }
        }
        Text(
            "얼굴 영상은 서버에서 암호화되어 관리자가 삭제할 때까지 보존됩니다.",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun RppgResultCard(result: RppgJobResult, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), border = BorderStroke(1.dp, Color(0xFFE1E7EC))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("카메라 rPPG 측정", style = MaterialTheme.typography.titleMedium)
            RppgCompactResult(result)
        }
    }
}

@Composable
private fun RppgCompactResult(result: RppgJobResult) {
    if (result.status == "completed") {
        Text("갈망 단계: ${result.classIndex?.let(::cameraCravingLabel) ?: "확인 불가"}")
        val classOneProbability = when (result.classIndex) {
            0 -> result.confidence?.let { 1f - it }
            1 -> result.confidence
            else -> null
        }
        Text("갈망 가능성: ${cravingProbabilityLabel(classOneProbability)}")
        Text("심박수: ${result.heartRateBpm?.let { "%.1f BPM".format(it) } ?: "측정 불가"}")
        Text("품질: ${result.qualityScore?.let { "%.2f".format(it) } ?: "확인 불가"}")
        Text("처리시간: ${result.processingMs?.let { "$it ms" } ?: "확인 불가"}")
    } else {
        Text(if (result.status == "retry_required") "영상 품질이 부족해 새 촬영이 필요합니다" else "분석 실패")
    }
}

private fun phaseLabel(phase: RppgCapturePhase): String = when (phase) {
    RppgCapturePhase.IDLE -> "측정 준비"
    RppgCapturePhase.FINDING_FACE -> "얼굴 찾는 중"
    RppgCapturePhase.STABILIZING -> "얼굴 안정화"
    RppgCapturePhase.RECORDING -> "20초 촬영 중"
    RppgCapturePhase.UPLOADING -> "업로드 중"
    RppgCapturePhase.ANALYZING -> "분석 대기"
    RppgCapturePhase.COMPLETED -> "측정 완료"
    RppgCapturePhase.RETRY_REQUIRED -> "재촬영 필요"
    RppgCapturePhase.FAILED -> "측정 실패"
}

private fun cameraCravingLabel(value: Int): String = when (value) {
    0 -> "0 낮음"
    1 -> "1 높음"
    else -> "$value 알 수 없음"
}

private val CAPTURE_PHASES = setOf(
    RppgCapturePhase.FINDING_FACE,
    RppgCapturePhase.STABILIZING,
    RppgCapturePhase.RECORDING
)

private val PRE_ACCEPTED_SCREEN_PHASES = CAPTURE_PHASES + RppgCapturePhase.UPLOADING

private class RppgCameraController(
    private val context: Context,
    private val onStability: (Long) -> Unit,
    private val onRecordingStarted: (File, Long) -> Unit,
    private val onRecordingFinished: (Long) -> Unit,
    private val onRecordingCancelled: (String) -> Unit
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = FaceStabilityTracker()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
    )
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cancelRequested = false
    private var currentFile: File? = null

    fun bind(owner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase -> useCase.setAnalyzer(executor, ::analyze) }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis, capture)
            }.onFailure {
                mainHandler.post { onRecordingCancelled("전면 카메라를 시작할 수 없습니다") }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun resetTracker() {
        tracker.reset()
        cancelRequested = false
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: androidx.camera.core.ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener(executor) { faces -> handleFaces(faces, image.width, image.height) }
            .addOnCompleteListener(executor) { proxy.close() }
    }

    private fun handleFaces(faces: List<Face>, width: Int, height: Int) {
        val face = faces.singleOrNull()
        val inside = face?.boundingBox?.let { isInsideGuide(it, width, height) } == true
        when (val event = tracker.update(System.currentTimeMillis(), faces.size, face?.trackingId, inside)) {
            is FaceStabilityEvent.Progress -> mainHandler.post { onStability(event.stableMs) }
            FaceStabilityEvent.StartRecording -> mainHandler.post(::startRecording)
            FaceStabilityEvent.CancelRecording -> mainHandler.post {
                cancelRecording()
                onRecordingCancelled("얼굴이 1초 이상 안내 영역을 벗어나 촬영을 취소했습니다")
            }
            FaceStabilityEvent.KeepRecording -> Unit
        }
    }

    private fun isInsideGuide(rect: Rect, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return rect.left >= width * 0.12f && rect.right <= width * 0.88f &&
            rect.top >= height * 0.08f && rect.bottom <= height * 0.92f
    }

    private fun startRecording() {
        if (recording != null) return
        val capture = videoCapture ?: return
        val directory = File(context.cacheDir, "rppg").apply { mkdirs() }
        val file = File(directory, "capture-${System.currentTimeMillis()}.mp4")
        currentFile = file
        cancelRequested = false
        val startedAt = System.currentTimeMillis()
        val pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> onRecordingStarted(file, startedAt)
                is VideoRecordEvent.Finalize -> {
                    mainHandler.removeCallbacksAndMessages(STOP_TOKEN)
                    recording = null
                    if (cancelRequested || event.hasError()) {
                        file.delete()
                    } else {
                        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                        onRecordingFinished(durationMs)
                    }
                }
            }
        }
        mainHandler.postAtTime({ recording?.stop() }, STOP_TOKEN, android.os.SystemClock.uptimeMillis() + RECORDING_MS)
    }

    fun cancelRecording() {
        cancelRequested = true
        mainHandler.removeCallbacksAndMessages(STOP_TOKEN)
        recording?.close()
        recording = null
        currentFile?.delete()
        currentFile = null
        tracker.reset()
    }

    fun close() {
        cancelRecording()
        provider?.unbindAll()
        detector.close()
        executor.shutdown()
    }

    companion object {
        private const val RECORDING_MS = RPPG_CAPTURE_SECONDS * 1_000L
        private val STOP_TOKEN = Any()
    }
}
