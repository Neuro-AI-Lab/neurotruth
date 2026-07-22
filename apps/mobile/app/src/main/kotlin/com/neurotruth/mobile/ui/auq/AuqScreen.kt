package com.neurotruth.mobile.ui.auq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
 * NT-06 · 자가보고 (AUQ).
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

    LaunchedEffect(state.done) {
        if (state.done) onContinueToChat()
    }

    ScreenScaffold(
        title = "지금의 상태",
        modifier = modifier,
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
                            text = if (state.isLastItem) "저장하고 대화하기" else "다음",
                            onClick = if (state.isLastItem) viewModel::onSubmit else viewModel::onNext,
                            enabled = if (state.isLastItem) state.canSubmit else state.canAdvance,
                            loading = state.isSubmitting,
                            contentDescription = if (state.isLastItem) {
                                "응답을 저장하고 대화 시작"
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
                            .semantics { contentDescription = "자가보고를 건너뛰고 대화 시작" },
                    ) {
                        Text("건너뛰고 대화하기", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
    ) {
        Text(
            text = state.progressLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = "전체 ${Auq.ITEM_COUNT}문항 중 ${state.index + 1}번째 문항"
            },
        )
        LinearProgressIndicator(
            progress = { state.progressFraction },
            modifier = Modifier.fillMaxWidth(),
        )

        SectionCard {
            Text(
                text = state.question.text,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )

            Auq.RESPONSE_LABELS.forEachIndexed { value, label ->
                ResponseRow(
                    label = label,
                    selected = state.selectedResponse == value,
                    enabled = !state.isSubmitting,
                    onSelect = { viewModel.onResponseSelected(value) },
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
}

/** The sentence is the whole choice; the API value behind it is never rendered. */
@Composable
private fun ResponseRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    // if/else — never an early return — so this content lambda always runs to a balanced end.
    if (selected) {
        Button(
            onClick = onSelect,
            enabled = enabled,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
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
            modifier = Modifier
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
