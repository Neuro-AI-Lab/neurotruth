package com.neurotruth.mobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing

/**
 * NT-07 · AI 챗봇.
 *
 * Free dialogue: no fixed questionnaire, no slot progress, no mandatory question order. Each AI
 * bubble offers 듣기/정지 through the device speech engine, and the global 응답 듣기 switch starts OFF.
 *
 * On a provider 502 the user's bubble is neither removed nor duplicated — the retry card sits below
 * it and sends the same message once.
 */
@Composable
fun ChatScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(NeuroTruthApp.from(LocalContext.current)),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Leaving the screen stops playback.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPlaybackForNavigation() }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = NeuroTruthSpacing.screenHorizontal),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = NeuroTruthSpacing.screenVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "AI 챗봇", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(
                onClick = viewModel::finishSession,
                enabled = state.sessionId != null,
                modifier = Modifier
                    .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                    .semantics { contentDescription = "대화 종료" },
            ) {
                Text("종료", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider()

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isPreparing -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(32.dp)
                            .semantics { contentDescription = "대화를 준비하고 있어요" },
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        vertical = NeuroTruthSpacing.screenVertical,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            speaking = state.speakingMessageId == message.id,
                            ttsAvailable = state.ttsAvailable,
                            onSpeak = { viewModel.onSpeak(message) },
                            onStop = viewModel::onStopSpeaking,
                        )
                    }

                    state.pendingRetry?.let { pending ->
                        item(key = "retry_${pending.clientMessageId}") {
                            RetryCard(
                                enabled = !state.isSending,
                                onRetry = viewModel::retryPending,
                            )
                        }
                    }

                    state.errorMessage?.let { message ->
                        item(key = "error") {
                            ErrorCard(
                                message = message,
                                showReconnect = state.sessionId == null,
                                onReconnect = viewModel::retryOpenSession,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NeuroTruthSpacing.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "응답 듣기", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = state.autoReadEnabled,
                onCheckedChange = viewModel::onAutoReadChanged,
                enabled = state.ttsAvailable,
                modifier = Modifier.semantics {
                    contentDescription = "새 답변 자동 읽기 " +
                        "${if (state.autoReadEnabled) "켜짐" else "꺼짐"}, 두 번 눌러 변경"
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = NeuroTruthSpacing.screenVertical),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::onDraftChanged,
                placeholder = { Text("메시지를 입력하세요") },
                enabled = !state.isPreparing,
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "메시지 입력란" },
            )
            Button(
                onClick = viewModel::send,
                enabled = state.canSend,
                modifier = Modifier
                    .widthIn(min = 72.dp)
                    .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                    .semantics { contentDescription = "메시지 전송" },
            ) {
                if (state.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("전송", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    speaking: Boolean,
    ttsAvailable: Boolean,
    onSpeak: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.86f),
            colors = CardDefaults.cardColors(
                containerColor = if (message.fromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (message.fromUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "${if (message.fromUser) "내 메시지" else "AI 응답"}: ${message.text}"
                    },
                )
                if (!message.fromUser && ttsAvailable) {
                    TextButton(
                        onClick = if (speaking) onStop else onSpeak,
                        modifier = Modifier
                            .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                            .semantics {
                                contentDescription = if (speaking) {
                                    "이 답변 읽기 정지"
                                } else {
                                    "이 답변 듣기"
                                }
                            },
                    ) {
                        Text(if (speaking) "정지" else "듣기")
                    }
                }
            }
        }
    }
}

/** The user's bubble above this card is untouched; the retry sends the same message once. */
@Composable
private fun RetryCard(enabled: Boolean, onRetry: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(NeuroTruthSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "답변을 불러오지 못했어요", style = MaterialTheme.typography.titleMedium)
            Text(text = "보낸 메시지는 그대로 유지됩니다.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                    .semantics { contentDescription = "같은 메시지로 응답 다시 받기" },
            ) {
                Text("응답 다시 받기", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, showReconnect: Boolean, onReconnect: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(NeuroTruthSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = "오류: $message" },
            )
            if (showReconnect) {
                OutlinedButton(
                    onClick = onReconnect,
                    modifier = Modifier
                        .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                        .semantics { contentDescription = "대화 다시 연결" },
                ) {
                    Text("다시 시도")
                }
            }
        }
    }
}
