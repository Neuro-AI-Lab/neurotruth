package com.neurotruth.mobile.ui.rppg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.FaceStabilityEvent
import com.neurotruth.mobile.core.FaceStabilityTracker
import com.neurotruth.mobile.core.RppgCapturePhase
import com.neurotruth.mobile.core.RppgContract
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The screen is always dark regardless of the system theme — the preview is the whole surface. */
private val CaptureBackground = Color(0xFF0B0F12)
private val CapturePanel = Color(0xFF161C21)
private val CaptureOnDark = Color(0xFFF2F5F7)
private val CaptureOnDarkMuted = Color(0xFFA9B4BC)
private val GuideIdle = Color(0xFFF2F5F7)
private val GuideStable = Color(0xFF3FBFA0)
private val GuideRecording = Color(0xFFD2544F)

/**
 * NT-04R · 얼굴 20초 측정.
 *
 * [onCompleted] fires only after the analysed result has been persisted **and** the route-once
 * claim succeeded, so a duplicate completion event — or a second process observing the same job —
 * never opens the conversation twice. Every other exit, including a cancelled capture and an
 * unrecoverable failure, goes through [onCancelled].
 *
 * Face timing is entirely [FaceStabilityTracker]'s: one face held inside the guide for a second
 * starts the recording and a full second of continuous loss cancels it, which is what keeps a blink
 * from discarding a 20-second take.
 */
@Composable
fun RppgCaptureScreen(
    onCompleted: () -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: RppgCaptureViewModel = viewModel(
        factory = RppgCaptureViewModel.factory(NeuroTruthApp.from(context)),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentPhase by rememberUpdatedState(state.phase)

    // Consent and the OS permission are separate gates. Consent is checked by the ViewModel against
    // the server snapshot; the camera permission is asked for here, the first time the feature is
    // opened, because no other screen in the app is allowed to request it.
    var permission by remember {
        mutableStateOf(
            if (context.hasCameraPermission()) {
                CameraPermissionState.GRANTED
            } else {
                CameraPermissionState.REQUESTING
            },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permission = when {
            granted -> CameraPermissionState.GRANTED
            // No rationale after a denial means the system will not show the dialog again, so the
            // only remaining path is the app's settings page.
            context.canRequestCameraPermissionAgain() -> CameraPermissionState.DENIED
            else -> CameraPermissionState.PERMANENTLY_DENIED
        }
    }

    LaunchedEffect(permission) {
        if (permission == CameraPermissionState.REQUESTING) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val controller = remember {
        RppgCameraController(
            context = context,
            onStability = viewModel::onStability,
            onRecordingStarted = viewModel::onRecordingStarted,
            onRecordingFinished = viewModel::onRecordingFinished,
            onRecordingCancelled = viewModel::onCaptureCancelled,
            onCameraUnavailable = viewModel::onCameraUnavailable,
        )
    }

    val cameraReady = permission == CameraPermissionState.GRANTED && state.cameraActive
    LaunchedEffect(cameraReady) {
        if (cameraReady) controller.bind(lifecycleOwner, previewView) else controller.release()
    }
    // A new attempt must not inherit the previous attempt's stability progress.
    LaunchedEffect(state.phase == RppgCapturePhase.FINDING_FACE) {
        if (state.phase == RppgCapturePhase.FINDING_FACE) controller.resetTracker()
    }
    LaunchedEffect(state.routeToChat) {
        if (state.routeToChat) onCompleted()
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && currentPhase.isCancellable()) {
                controller.cancelRecording()
                viewModel.onCaptureCancelled(RppgCaptureCopy.CANCELLED_BY_BACKGROUND)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.close()
        }
    }

    if (permission != CameraPermissionState.GRANTED) {
        CameraPermissionGate(
            permission = permission,
            onRetry = { permission = CameraPermissionState.REQUESTING },
            onOpenSettings = context::openApplicationSettings,
            onCancelled = onCancelled,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CaptureBackground),
        verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (state.cameraActive) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .fillMaxHeight(0.62f)
                        .border(3.dp, state.guideColor(), RoundedCornerShape(44.dp))
                        .semantics { contentDescription = "얼굴 안내 영역" },
                )
            } else if (state.isBusy) {
                CircularProgressIndicator(color = CaptureOnDark)
            }
        }

        CapturePanel(
            state = state,
            onRecapture = viewModel::beginCapture,
            onCancel = {
                controller.cancelRecording()
                if (state.phase.isCancellable()) {
                    viewModel.onCaptureCancelled(RppgCaptureCopy.CANCELLED_BY_BACKGROUND)
                }
                onCancelled()
            },
        )

        Text(
            text = RppgCaptureCopy.RETENTION_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = CaptureOnDarkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NeuroTruthSpacing.screenHorizontal, vertical = 10.dp)
                .semantics { contentDescription = RppgCaptureCopy.RETENTION_NOTICE },
        )
    }
}

/** The OS camera permission, which NT-03 keeps separate from the four consents. */
private enum class CameraPermissionState { REQUESTING, GRANTED, DENIED, PERMANENTLY_DENIED }

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** False once the system has stopped offering the dialog — the settings page is then the only path. */
private fun Context.canRequestCameraPermissionAgain(): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

private fun Context.openApplicationSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Shown while the permission is being asked for and after a denial.
 *
 * A denial never leaves the screen dead: it always carries a way back to Home, and a permanent
 * denial additionally offers the system settings page, which is the only place the decision can
 * still be changed.
 */
@Composable
private fun CameraPermissionGate(
    permission: CameraPermissionState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CaptureBackground)
            .padding(NeuroTruthSpacing.screenHorizontal),
        verticalArrangement = Arrangement.Center,
    ) {
        val message = when (permission) {
            CameraPermissionState.REQUESTING -> RppgCaptureCopy.PERMISSION_REQUESTING
            CameraPermissionState.DENIED -> RppgCaptureCopy.PERMISSION_DENIED
            CameraPermissionState.PERMANENTLY_DENIED -> RppgCaptureCopy.PERMISSION_BLOCKED
            CameraPermissionState.GRANTED -> ""
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CapturePanel, RoundedCornerShape(24.dp))
                .padding(NeuroTruthSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
        ) {
            Text(
                text = "카메라 권한",
                style = MaterialTheme.typography.titleLarge,
                color = CaptureOnDark,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = CaptureOnDarkMuted,
                modifier = Modifier.semantics { contentDescription = message },
            )

            // if/else, never an early return@Column: bailing out of a layout content lambda after
            // emitting composables corrupts the slot table and crashes the next recomposition.
            if (permission == CameraPermissionState.REQUESTING) {
                CircularProgressIndicator(color = CaptureOnDark)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
                ) {
                    if (permission == CameraPermissionState.PERMANENTLY_DENIED) {
                        Button(
                            onClick = onOpenSettings,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                                .semantics { contentDescription = "앱 설정 화면 열기" },
                        ) { Text("설정 열기") }
                    } else {
                        Button(
                            onClick = onRetry,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                                .semantics { contentDescription = "카메라 권한 다시 요청" },
                        ) { Text("다시 요청") }
                    }
                    OutlinedButton(
                        onClick = onCancelled,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                            .semantics { contentDescription = "측정 화면 닫기" },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CaptureOnDark),
                    ) { Text("홈으로") }
                }

                Text(
                    text = RppgCaptureCopy.RETENTION_NOTICE,
                    style = MaterialTheme.typography.bodySmall,
                    color = CaptureOnDarkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun RppgCapturePhase.isCancellable(): Boolean =
    this == RppgCapturePhase.FINDING_FACE ||
        this == RppgCapturePhase.STABILIZING ||
        this == RppgCapturePhase.RECORDING ||
        this == RppgCapturePhase.UPLOADING

private fun RppgCaptureUiState.guideColor(): Color = when {
    isRecording -> GuideRecording
    stabilityProgress > 0f -> GuideStable
    else -> GuideIdle
}

/**
 * The lower panel.
 *
 * Remaining seconds and the capture progress are shown while recording; after the 3-minute polling
 * ceiling the slow-analysis notice replaces the spinner copy and `[홈으로]` becomes the way out.
 */
@Composable
private fun CapturePanel(
    state: RppgCaptureUiState,
    onRecapture: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NeuroTruthSpacing.screenHorizontal)
            .background(CapturePanel, RoundedCornerShape(20.dp))
            .padding(NeuroTruthSpacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
    ) {
        Text(
            text = state.phaseTitle,
            style = MaterialTheme.typography.titleLarge,
            color = CaptureOnDark,
            modifier = Modifier.semantics { contentDescription = "측정 단계 ${state.phaseTitle}" },
        )
        Text(
            text = state.guidance,
            style = MaterialTheme.typography.bodyMedium,
            color = CaptureOnDarkMuted,
        )

        if (state.phase == RppgCapturePhase.STABILIZING || state.phase == RppgCapturePhase.FINDING_FACE) {
            LinearProgressIndicator(
                progress = { state.stabilityProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "얼굴 안정화 진행" },
                color = GuideStable,
                trackColor = CaptureOnDarkMuted.copy(alpha = 0.3f),
            )
        }

        if (state.isRecording) {
            Text(
                text = "남은 시간 ${state.remainingSeconds}초",
                style = MaterialTheme.typography.headlineMedium,
                color = GuideRecording,
                modifier = Modifier.semantics {
                    contentDescription = "남은 시간 ${state.remainingSeconds}초"
                },
            )
            LinearProgressIndicator(
                progress = { state.captureProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "촬영 진행" },
                color = GuideRecording,
                trackColor = CaptureOnDarkMuted.copy(alpha = 0.3f),
            )
        }

        state.slowNotice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyMedium,
                color = CaptureOnDark,
                modifier = Modifier.semantics { contentDescription = notice },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
        ) {
            if (state.canRecapture) {
                Button(
                    onClick = onRecapture,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                        .semantics { contentDescription = "새로 측정 시작" },
                ) { Text("새로 측정") }
            }
            OutlinedButton(
                onClick = onCancel,
                shape = CircleShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                    .semantics { contentDescription = "측정 화면 닫기" },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CaptureOnDark),
            ) {
                Text(if (state.cameraActive) "취소" else "홈으로")
            }
        }
    }
}

/**
 * CameraX front-camera capture with ML Kit face detection.
 *
 * Tracking is enabled because [FaceStabilityTracker] keys on `trackingId`: without it a second
 * person stepping into frame could hand the capture to a different face mid-recording.
 *
 * The recorder is stopped by a posted token rather than by counting analyzer frames, and the real
 * recorded length is taken from the finalize event so the caller can validate it against
 * [RppgContract] before uploading anything.
 */
private class RppgCameraController(
    private val context: Context,
    private val onStability: (Long) -> Unit,
    private val onRecordingStarted: (File, Long) -> Unit,
    private val onRecordingFinished: (Long) -> Unit,
    private val onRecordingCancelled: (String) -> Unit,
    private val onCameraUnavailable: () -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = FaceStabilityTracker()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build(),
    )

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cancelRequested = false
    private var currentFile: File? = null
    private var bound = false

    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        if (bound) return
        bound = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull()
            // release() may have run while the future was still pending. bind()'s listener and
            // release() both run on the main thread, so re-checking bound here is enough to avoid
            // opening the camera onto a screen that has already torn down (e.g. backgrounded during
            // the first camera init).
            if (!bound) {
                runCatching { cameraProvider?.unbindAll() }
                return@addListener
            }
            if (cameraProvider == null) {
                onCameraUnavailable()
                return@addListener
            }
            provider = cameraProvider
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                    ),
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis,
                    capture,
                )
            }.onFailure { onCameraUnavailable() }
        }, ContextCompat.getMainExecutor(context))
    }

    fun resetTracker() {
        tracker.reset()
        cancelRequested = false
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener(executor) { faces -> handleFaces(faces, image.width, image.height) }
            .addOnFailureListener(executor) { }
            .addOnCompleteListener(executor) { proxy.close() }
    }

    private fun handleFaces(faces: List<Face>, width: Int, height: Int) {
        val face = faces.singleOrNull()
        val inside = face?.boundingBox?.let { isInsideGuide(it, width, height) } == true
        val event = tracker.update(
            nowMs = System.currentTimeMillis(),
            faceCount = faces.size,
            trackingId = face?.trackingId,
            insideGuide = inside,
        )
        when (event) {
            is FaceStabilityEvent.Progress -> mainHandler.post { onStability(event.stableMs) }
            FaceStabilityEvent.StartRecording -> mainHandler.post(::startRecording)
            FaceStabilityEvent.KeepRecording -> Unit
            FaceStabilityEvent.CancelRecording -> mainHandler.post {
                cancelRecording()
                onRecordingCancelled(RppgCaptureCopy.CANCELLED_BY_FACE)
            }
        }
    }

    /** The guide inset matches the on-screen frame, so what the user sees is what is enforced. */
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
                    currentFile = null
                    if (cancelRequested || event.hasError()) {
                        file.delete()
                    } else {
                        onRecordingFinished(event.recordingStats.recordedDurationNanos / 1_000_000L)
                    }
                }
                else -> Unit
            }
        }
        mainHandler.postAtTime(
            { recording?.stop() },
            STOP_TOKEN,
            SystemClock.uptimeMillis() + RppgContract.CAPTURE_MS,
        )
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

    fun release() {
        cancelRecording()
        provider?.unbindAll()
        bound = false
    }

    fun close() {
        release()
        detector.close()
        executor.shutdown()
    }

    private companion object {
        val STOP_TOKEN = Any()
    }
}
