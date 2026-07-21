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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.WatchConnectionState
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "홈", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                    .semantics { contentDescription = "설정 열기" },
            ) {
                Text("설정", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider()

        ProfileSummary(
            displayName = state.displayName,
            accountSummary = state.accountSummary,
            onDeveloperEntry = viewModel::onDeveloperEntryUnlocked,
        )

        CravingStateCard(state = state)

        WatchAndCameraCard(
            state = state,
            onOpenCameraMeasurement = onOpenCameraMeasurement,
        )

        state.errorMessage?.let { message ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(NeuroTruthSpacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = message, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = viewModel::refresh,
                        modifier = Modifier
                            .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                            .semantics { contentDescription = "다시 불러오기" },
                    ) { Text("다시 시도") }
                }
            }
        }
    }
}

/** Region 1. The header carries the hidden developer entry and no visible affordance for it. */
@Composable
private fun ProfileSummary(
    displayName: String,
    accountSummary: String,
    onDeveloperEntry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(NeuroTruthSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "프로필", style = MaterialTheme.typography.titleMedium)
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
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (state.hasMeasurement) {
                    "갈망 상태 ${state.stageLabel}. ${state.stageMessage}"
                } else {
                    state.cravingHiddenReason ?: "갈망 상태 ${state.stageLabel}"
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(NeuroTruthSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
        ) {
            if (state.cravingHiddenReason != null) {
                Text(
                    text = state.cravingHiddenReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                text = if (state.hasMeasurement) state.stageMessage else "아직 측정된 기록이 없어요.",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.stageLabel,
                        style = MaterialTheme.typography.titleLarge,
                        color = accent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(4.dp),
                    )
                }
                Text(
                    text = "연구용 모델의 구간 표시이며 진단이나 임상적 위험도를 의미하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

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
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "측정 시각과 기기: $origin" },
                )
            }
        }
    }
}

/**
 * Region 3.
 *
 * The camera action is enabled only when [CameraActionPolicy] says so; while it is blocked the
 * reason from `:core` is shown verbatim beside it. A checking or error Watch state never enables it.
 */
@Composable
private fun WatchAndCameraCard(
    state: HomeUiState,
    onOpenCameraMeasurement: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(NeuroTruthSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
        ) {
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
                }
                Button(
                    onClick = onOpenCameraMeasurement,
                    enabled = state.cameraEnabled,
                    modifier = Modifier
                        .widthIn(min = 132.dp)
                        .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                        .semantics {
                            contentDescription = state.cameraBlockedReason
                                ?.let { "카메라로 측정, 사용할 수 없음. $it" }
                                ?: "카메라로 측정 시작"
                        },
                ) {
                    Text("카메라로 측정", style = MaterialTheme.typography.labelLarge)
                }
            }

            state.cameraBlockedReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
