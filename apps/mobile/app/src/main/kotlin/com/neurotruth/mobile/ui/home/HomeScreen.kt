package com.neurotruth.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.WatchConnectionState
import com.neurotruth.mobile.ui.theme.CardTone
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
import com.neurotruth.mobile.ui.theme.SectionCard
import com.neurotruth.mobile.ui.theme.cravingAccent
import kotlinx.coroutines.withTimeoutOrNull

/** The hidden developer entry is a 2.5-second hold, far past the platform long-press threshold. */
private const val DEVELOPER_LONG_PRESS_MS = 2_500L

/**
 * NT-04 · 홈. Exactly three regions and nothing else.
 *
 * Deliberately absent: PPG waveform, raw signal, server status, permission-check buttons, visible
 * developer buttons, and any probability percentage. Raw signal and detailed charts belong to the
 * dashboard.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenCameraMeasurement: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(NeuroTruthApp.from(LocalContext.current)),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Watch connection is re-confirmed on every return to the foreground rather than trusted from
    // before the app was backgrounded.
    DisposableEffect(lifecycleOwner, viewModel) {
        viewModel.onScreenActive(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onScreenActive(true)
                    viewModel.onResumed()
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Leaving the tab disposes this composable without an ON_STOP, so pause the poll here too.
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenActive(false)
        }
    }

    if (state.developerEntryUnlocked) {
        AlertDialog(
            onDismissRequest = viewModel::onDeveloperEntryDismissed,
            confirmButton = {
                TextButton(
                    onClick = viewModel::onDeveloperEntryDismissed,
                    modifier = Modifier.semantics { contentDescription = "개발자 안내 닫기" },
                ) { Text("닫기") }
            },
            title = { Text("개발자 화면") },
            text = { Text("온디바이스 테스트 화면은 아직 이 빌드에 포함되어 있지 않아요.") },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = NeuroTruthSpacing.screenHorizontal,
                    vertical = NeuroTruthSpacing.screenVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenCards),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NeuroTruthSpacing.titleTop, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "홈",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() },
                )
                FilledTonalIconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(NeuroTruthSpacing.minTouchTarget)
                        .semantics { contentDescription = "설정 열기" },
                ) {
                    Icon(imageVector = Icons.Outlined.Settings, contentDescription = null)
                }
            }

            ProfileSummary(
                displayName = state.displayName,
                accountSummary = state.accountSummary,
                onDeveloperEntry = viewModel::onDeveloperEntryUnlocked,
            )

            state.monitoringNotice?.let { MonitoringBanner(message = it, label = "측정 상태 안내") }
            state.droppedNotice?.let { MonitoringBanner(message = it, label = "전송 안내") }

            CravingStateCard(state = state)

            WatchAndCameraCard(
                state = state,
                onMeasurementToggle = {
                    viewModel.requestMeasurement(
                        start = state.measurementControl.status !=
                            com.neurotruth.mobile.service.MeasurementControlStatus.STARTED,
                    )
                },
                onOpenCameraMeasurement = onOpenCameraMeasurement,
            )

            state.errorMessage?.let { message ->
                SectionCard(tone = CardTone.Low) {
                    Text(text = message, style = MaterialTheme.typography.bodyMedium)
                    com.neurotruth.mobile.ui.theme.SecondaryButton(
                        text = "다시 시도",
                        onClick = viewModel::refresh,
                        contentDescription = "다시 불러오기",
                    )
                }
            }
        }
    }
}

/**
 * A banner the services layer published — a paused measurement or an evicted retry queue.
 *
 * It states why measurement is not running and never carries a craving value or a percentage.
 */
@Composable
private fun MonitoringBanner(message: String, label: String) {
    SectionCard(
        tone = CardTone.Alert,
        modifier = Modifier.semantics { contentDescription = "$label: $message" },
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Region 1. The header carries the hidden developer entry and no visible affordance for it. */
@Composable
private fun ProfileSummary(
    displayName: String,
    accountSummary: String,
    onDeveloperEntry: () -> Unit,
) {
    SectionCard(
        tone = CardTone.Highlight,
        contentGap = 6.dp,
        modifier = Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val releasedInTime = withTimeoutOrNull(DEVELOPER_LONG_PRESS_MS) {
                        waitForUpOrCancellation()
                    }
                    if (releasedInTime == null) onDeveloperEntry()
                }
            }
            .semantics { contentDescription = "프로필 요약" },
    ) {
        Text(text = "프로필", style = MaterialTheme.typography.labelLarge)
        Text(
            text = displayName.ifBlank { "사용자" } + "님",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = accountSummary.ifBlank { "내 계정과 동의 상태" },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Region 2.
 *
 * Shows the band label and its copy, the framing note, and the measurement time and device — never
 * a percentage, and never 0% or "낮음" when there is no measurement.
 */
@Composable
private fun CravingStateCard(state: HomeUiState) {
    val accent = cravingAccent(state.stage)
    SectionCard(
        tone = CardTone.Hero,
        elevation = 3.dp,
        modifier = Modifier.semantics {
            contentDescription = if (state.hasMeasurement) {
                "갈망 상태 ${state.stageLabel}. ${state.stageMessage}"
            } else {
                state.cravingHiddenReason ?: "아직 측정된 기록이 없어요"
            }
        },
        contentGap = NeuroTruthSpacing.betweenRows,
    ) {
        Text(
            text = "지금 상태",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Three balanced branches (never an early return@Column): a consent/feature reason, the
        // no-measurement invitation, or the measured hero. Only the measured branch draws the
        // accent disc — an empty state no longer fills the card with a grey circle.
        when {
            state.cravingHiddenReason != null -> {
                Text(
                    text = state.cravingHiddenReason,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            !state.hasMeasurement -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "아직 측정된 기록이 없어요",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Watch를 연결하거나 아래에서 얼굴로 측정하면 여기에 지금 상태가 표시돼요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenCards),
                ) {
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.stageLabel,
                            style = MaterialTheme.typography.headlineMedium,
                            color = accent,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        text = state.stageMessage,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = "연구용 모델의 구간 표시이며 진단이나 임상적 위험도를 의미하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.recommendsConversation) {
                    AssistChip(
                        onClick = { },
                        label = { Text("대화 권장") },
                        modifier = Modifier.semantics {
                            contentDescription = "대화 권장 표시"
                        },
                    )
                }

                state.measurementOrigin?.let { origin ->
                    Text(
                        text = origin,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "측정 시각과 기기: $origin" },
                    )
                }
            }
        }
    }
}

/**
 * Region 3.
 *
 * The camera action follows [CameraActionPolicy] through [HomeCameraEntryPolicy]; while it is
 * blocked the reason from `:core` is shown verbatim beside it. A checking or error Watch state never
 * enables it. A missing OS camera permission is the one non-blocking case — the button stays
 * tappable and NT-04R asks for the permission on entry.
 */
@Composable
private fun WatchAndCameraCard(
    state: HomeUiState,
    onMeasurementToggle: () -> Unit,
    onOpenCameraMeasurement: () -> Unit,
) {
    SectionCard(tone = CardTone.Low, contentGap = NeuroTruthSpacing.betweenRows) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Watch 연결",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.watchStateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.watchState == WatchConnectionState.CONNECTED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Watch 연결 상태: ${state.watchStateLabel}"
                    },
                )
                Text(
                    text = when (state.measurementControl.status) {
                        com.neurotruth.mobile.service.MeasurementControlStatus.STARTED ->
                            if (state.monitoringRunning) "측정·자동 전송 중" else "Watch 응답 확인 중"
                        com.neurotruth.mobile.service.MeasurementControlStatus.REQUESTING_START ->
                            "Watch에 시작 요청 중"
                        com.neurotruth.mobile.service.MeasurementControlStatus.REQUESTING_STOP ->
                            "Watch에 중지 요청 중"
                        com.neurotruth.mobile.service.MeasurementControlStatus.CONFIRMATION_REQUIRED ->
                            "Watch 알림에서 시작을 확인해 주세요"
                        com.neurotruth.mobile.service.MeasurementControlStatus.ERROR ->
                            "측정 요청을 완료하지 못했어요"
                        com.neurotruth.mobile.service.MeasurementControlStatus.STOPPED -> "측정 대기"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onMeasurementToggle,
                enabled = state.measurementButtonEnabled,
                modifier = Modifier
                    .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                    .semantics {
                        contentDescription = state.measurementButtonLabel
                    },
            ) {
                Text(state.measurementButtonLabel)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = onOpenCameraMeasurement,
                enabled = state.cameraEnabled,
                shape = CircleShape,
                modifier = Modifier
                    .widthIn(min = 132.dp)
                    .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                    .semantics {
                        contentDescription = when {
                            state.cameraEnabled && state.cameraNotice != null ->
                                "카메라로 측정 시작. ${state.cameraNotice}"
                            state.cameraEnabled -> "카메라로 측정 시작"
                            else -> "카메라로 측정, 사용할 수 없음. ${state.cameraNotice.orEmpty()}"
                        }
                    },
            ) {
                Text("카메라로 측정", style = MaterialTheme.typography.labelLarge)
            }
        }

        state.cameraNotice?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
