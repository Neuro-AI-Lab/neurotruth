package com.neurotruth.mobile.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.ui.theme.CardTone
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
import com.neurotruth.mobile.ui.theme.SectionCard
import com.neurotruth.mobile.ui.theme.SecondaryButton

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
    onRequiresAuq: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(NeuroTruthApp.from(LocalContext.current)),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A newly created session offers NT-06 before dialogue; a resumed one goes straight back to it.
    LaunchedEffect(state.requiresAuq) {
        if (state.requiresAuq) {
            viewModel.onAuqNavigated()
            onRequiresAuq()
        }
    }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onMicPermissionChanged(true)
            viewModel.onMicToggled()
        } else {
            viewModel.onMicPermissionDenied()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onMicPermissionChanged(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    // Leaving the screen stops playback and deletes any temporary recording.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPlaybackForNavigation()
            viewModel.releaseRecording()
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    LaunchedEffect(state.finished) {
        if (state.finished) {
            // Consume the flag before leaving so a later return to the preserved chat tab does not
            // re-fire this effect and bounce the user out.
            viewModel.onFinishedNavigated()
            onFinished()
        }
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
                .padding(top = NeuroTruthSpacing.titleTop, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "AI 챗봇",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedButton(
                onClick = viewModel::finishSession,
                enabled = state.sessionId != null && !state.isSending && !state.isFinishing,
                shape = CircleShape,
                modifier = Modifier
                    .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                    .semantics { contentDescription = "대화 종료" },
            ) {
                if (state.isFinishing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("종료", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

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

        if (state.isRecording) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "녹음 중 · 최대 30초",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = "녹음 중, 최대 30초" },
                )
                TextButton(
                    onClick = viewModel::cancelRecording,
                    modifier = Modifier
                        .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                        .semantics { contentDescription = "녹음 취소" },
                ) {
                    Text("취소")
                }
            }
        }

        // STT status is independent of the typed draft: a successful transcript is sent as its own
        // voice bubble, while failures leave this field usable and unchanged.
        state.voiceNotice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .semantics { contentDescription = "음성 입력 안내: $notice" },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = NeuroTruthSpacing.screenVertical),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.voiceConsentGranted) {
                MicButton(
                    recording = state.isRecording,
                    transcribing = state.isTranscribing,
                    enabled = state.micEnabled || state.isRecording,
                    onClick = {
                        when {
                            state.isRecording || state.micPermissionGranted ->
                                viewModel.onMicToggled()

                            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
            TextField(
                value = state.draft,
                onValueChange = viewModel::onDraftChanged,
                placeholder = { Text("메시지를 입력하세요") },
                enabled = !state.isPreparing,
                maxLines = 4,
                shape = MaterialTheme.shapes.large,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "메시지 입력란" },
            )
            Button(
                onClick = viewModel::send,
                enabled = state.canSend,
                shape = CircleShape,
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

/**
 * The microphone never replaces the keyboard. It is absent without `voice` consent, and a denied
 * permission or a failed transcription only produces a notice — the field beside it stays usable.
 */
@Composable
private fun MicButton(
    recording: Boolean,
    transcribing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled && !transcribing,
        modifier = Modifier
            .size(NeuroTruthSpacing.minTouchTarget)
            .semantics {
                contentDescription = when {
                    transcribing -> "음성을 문자로 바꾸는 중"
                    recording -> "녹음 중지하고 문자로 바꾸기"
                    else -> "음성으로 입력하기, 최대 30초"
                }
            },
    ) {
        if (transcribing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = null,
            )
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
    // Asymmetric large radius with one tight corner nearest the sender: bottom-end for the user's
    // right-aligned bubble, bottom-start for the assistant's left-aligned one.
    val bubbleShape = if (message.fromUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 4.dp, bottomStart = 20.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 4.dp)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = bubbleShape,
            color = if (message.fromUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (message.fromUser) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
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
    SectionCard(tone = CardTone.Warm, contentGap = 8.dp) {
        Text(text = "답변을 불러오지 못했어요", style = MaterialTheme.typography.titleMedium)
        Text(text = "보낸 메시지는 그대로 유지됩니다.", style = MaterialTheme.typography.bodyMedium)
        SecondaryButton(
            text = "응답 다시 받기",
            onClick = onRetry,
            enabled = enabled,
            contentDescription = "같은 메시지로 응답 다시 받기",
        )
    }
}

@Composable
private fun ErrorCard(message: String, showReconnect: Boolean, onReconnect: () -> Unit) {
    SectionCard(tone = CardTone.Alert, contentGap = 8.dp) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = "오류: $message" },
        )
        if (showReconnect) {
            SecondaryButton(
                text = "다시 시도",
                onClick = onReconnect,
                contentDescription = "대화 다시 연결",
            )
        }
    }
}
