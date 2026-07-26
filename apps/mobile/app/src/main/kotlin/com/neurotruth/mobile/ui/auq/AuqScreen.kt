package com.neurotruth.mobile.ui.auq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.Auq
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
import com.neurotruth.mobile.ui.theme.PrimaryButton
import com.neurotruth.mobile.ui.theme.ScreenScaffold
import com.neurotruth.mobile.ui.theme.SectionCard
import com.neurotruth.mobile.ui.theme.SecondaryButton

/**
 * NT-06 · 자기설문 (AUQ).
 *
 * One item per screen with `1 / 8` progress, seven sentence-form choices and no numbers anywhere on
 * screen. `[건너뛰고 대화하기]` is offered on every item and never blocks the conversation.
 *
 * Deliberately absent: any low/medium/high interpretation, any band, any score readout beyond the
 * neutral note that a higher score meant higher self-reported craving at the time.
 */
@Composable
fun AuqScreen(
    onContinueToChat: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuqViewModel = viewModel(
        factory = AuqViewModel.factory(NeuroTruthApp.from(LocalContext.current)),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.shouldOpenChat) {
        if (state.shouldOpenChat) onContinueToChat()
    }

    val resultScore = state.resultScore
    if (resultScore != null) {
        AuqResultScreen(
            score = resultScore,
            onContinueToChat = onContinueToChat,
            modifier = modifier,
        )
        return
    }

    var infoVisible by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = "자기설문",
        modifier = modifier,
        scroll = true,
        contentGap = 8.dp,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = NeuroTruthSpacing.screenHorizontal,
                            vertical = NeuroTruthSpacing.screenVertical,
                        ),
                    verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
                    ) {
                        if (!state.isFirstItem) {
                            SecondaryButton(
                                text = "이전",
                                onClick = viewModel::onPrevious,
                                enabled = !state.isSubmitting,
                                contentDescription = "이전 문항으로",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        PrimaryButton(
                            text = if (state.isLastItem) "결과 확인" else "다음",
                            onClick = if (state.isLastItem) viewModel::onSubmit else viewModel::onNext,
                            enabled = if (state.isLastItem) state.canSubmit else state.canAdvance,
                            loading = state.isSubmitting,
                            contentDescription = if (state.isLastItem) {
                                "응답을 저장하고 결과 확인"
                            } else {
                                "다음 문항으로"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Available on every item, including while a submission has just failed.
                    TextButton(
                        onClick = viewModel::onSkip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                            .semantics { contentDescription = "자기설문을 건너뛰고 대화 시작" },
                    ) {
                        Text("건너뛰고 대화하기", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = state.progressLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "전체 ${Auq.ITEM_COUNT}문항 중 ${state.index + 1}번째 문항"
                    },
            )
            IconButton(
                onClick = { infoVisible = true },
                modifier = Modifier.semantics { contentDescription = "AUQ 자기설문 설명" },
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null)
            }
        }
        LinearProgressIndicator(
            progress = { state.progressFraction },
            modifier = Modifier.fillMaxWidth(),
        )

        SectionCard(
            contentPadding = PaddingValues(12.dp),
            contentGap = 4.dp,
        ) {
            Text(
                text = state.question.text,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )

            Auq.RESPONSE_LABELS.forEachIndexed { index, label ->
                ResponseRow(
                    label = label,
                    selected = state.selectedResponse == index,
                    enabled = !state.isSubmitting,
                    onSelect = { viewModel.onResponseSelected(index) },
                )
            }
        }

        Text(
            text = "점수가 높을수록 당시 음주 욕구 관련 응답이 높았습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "언제든 건너뛰고 대화를 시작할 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "저장 오류: $message" },
            )
        }
    }
    if (infoVisible) {
        AlertDialog(
            onDismissRequest = { infoVisible = false },
            confirmButton = {
                TextButton(onClick = { infoVisible = false }) { Text("확인") }
            },
            title = { Text("AUQ 자기설문 안내") },
            text = {
                Text(
                    "AUQ는 음주 욕구를 확인하는 8문항·7점 척도입니다. " +
                        "현재 문항은 AUQ를 참고한 연구용 한국어 adaptation이며, " +
                        "결과는 진단이나 임상 판정이 아닙니다.",
                )
            },
        )
    }
}

@Composable
private fun AuqResultScreen(
    score: Int,
    onContinueToChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(title = "자기설문 결과", modifier = modifier, scroll = false) {
        SectionCard {
            Text(
                text = "총점 $score/${Auq.SCALE_MAX}",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics {
                    contentDescription = "자기설문 총점 ${score}점, ${Auq.SCALE_MAX}점 만점"
                },
            )
            Text(
                text = "점수가 높을수록 당시 음주 욕구 관련 응답이 높았습니다.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "이 결과는 연구용 기록이며 진단이나 임상 판정이 아닙니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryButton(
            text = "챗봇으로 이동",
            onClick = onContinueToChat,
            contentDescription = "자기설문 결과를 확인하고 챗봇으로 이동",
        )
    }
}

/** The sentence is the whole choice; the API value behind it is never rendered. */
@Composable
private fun ResponseRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // if/else — never an early return — so this content lambda always runs to a balanced end.
    if (selected) {
        Button(
            onClick = onSelect,
            enabled = enabled,
            shape = MaterialTheme.shapes.large,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                .semantics {
                    contentDescription = "$label, 선택됨, 두 번 눌러 선택"
                },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        OutlinedButton(
            onClick = onSelect,
            enabled = enabled,
            shape = MaterialTheme.shapes.large,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                .semantics {
                    contentDescription = "$label, 두 번 눌러 선택"
                },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
