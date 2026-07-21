package com.neurotruth.mobile.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ChatRetryPolicy
import com.neurotruth.mobile.core.DialogueFailure
import com.neurotruth.mobile.core.INPUT_MODALITY_TEXT
import com.neurotruth.mobile.core.PendingChatRetry
import com.neurotruth.mobile.data.AlertSessionEntry
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.ApiResponse
import com.neurotruth.mobile.data.ActiveSessionStore
import com.neurotruth.mobile.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
)

data class ChatUiState(
    val sessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    /** PRD §NT-07: the global auto-read switch defaults to OFF. */
    val autoReadEnabled: Boolean = false,
    val isPreparing: Boolean = true,
    val isSending: Boolean = false,
    val pendingRetry: PendingChatRetry? = null,
    val speakingMessageId: String? = null,
    val ttsAvailable: Boolean = false,
    val errorMessage: String? = null,
    val finished: Boolean = false,
) {
    val canSend: Boolean
        get() = sessionId != null && !isSending && !isPreparing && draft.isNotBlank() &&
            pendingRetry == null
}

/**
 * Device speech synthesis. There is no server TTS route.
 *
 * Only one response plays at a time, and the caller stops playback on leaving the screen or logging
 * out. A synthesis failure never removes the text answer.
 */
class ChatTtsController(
    context: Context,
    private val onSpeakingChanged: (String?) -> Unit,
    private val onReady: (Boolean) -> Unit,
) {
    private var engine: TextToSpeech? = null

    @Volatile
    private var ready: Boolean = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.KOREAN
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = onSpeakingChanged(utteranceId)
                    override fun onDone(utteranceId: String?) = onSpeakingChanged(null)

                    @Deprecated("Kept for the platform interface")
                    override fun onError(utteranceId: String?) = onSpeakingChanged(null)

                    override fun onError(utteranceId: String?, errorCode: Int) =
                        onSpeakingChanged(null)
                })
            }
            onReady(ready)
        }
    }

    fun speak(messageId: String, text: String) {
        if (!ready) return
        engine?.stop()
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, messageId)
    }

    fun stop() {
        engine?.stop()
        onSpeakingChanged(null)
    }

    fun shutdown() {
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        ready = false
    }
}

/**
 * NT-07 · AI 챗봇 (자유 대화).
 *
 * The retry contract lives in [ChatRetryPolicy]: a provider 502 keeps the user's bubble in place and
 * offers exactly one manual retry with the same `clientMessageId`, the same body and the same
 * modality. Editing the body is a new message with a new id, and a 409 never causes the app to mint
 * a new id.
 */
class ChatViewModel(
    private val app: NeuroTruthApp,
) : ViewModel() {

    private val sessionRepository = SessionRepository(
        client = app.apiClient,
        endpoints = app.endpoints,
        activeSessionStore = ActiveSessionStore(app),
    )

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val tts = ChatTtsController(
        context = app,
        onSpeakingChanged = { id -> _state.update { it.copy(speakingMessageId = id) } },
        onReady = { available -> _state.update { it.copy(ttsAvailable = available) } },
    )

    init {
        openSession()
    }

    /**
     * PRD §5.3. The 챗봇 tab is a `manual_checkin`; the entry point, not this call site, carries the
     * type. NT-06 has not shipped, so a newly created session goes straight to dialogue with no AUQ
     * and no placeholder request — the discriminator is still evaluated so the branch is already
     * correct when NT-06 lands.
     */
    private fun openSession() {
        _state.update { it.copy(isPreparing = true, errorMessage = null) }
        viewModelScope.launch {
            // An alert tap arms AlertSessionEntry, so the same call labels the session
            // alert_checkin and forwards its triggerAlertId; otherwise it is a manual check-in.
            val (entryPoint, alertId) = AlertSessionEntry.consume()
            val outcome = withContext(Dispatchers.IO) {
                runCatching { sessionRepository.ensureSession(entryPoint, alertId) }
            }
            outcome
                .onSuccess { entry ->
                    val opening = entry.assistantText ?: RESUMED_NOTICE
                    _state.update {
                        it.copy(
                            sessionId = entry.sessionId,
                            isPreparing = false,
                            messages = listOf(
                                ChatMessage(
                                    id = "opening",
                                    fromUser = false,
                                    text = opening,
                                ),
                            ),
                        )
                    }
                    restorePendingRetry()
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isPreparing = false,
                            errorMessage = "대화를 시작하지 못했어요. 잠시 후 다시 시도해 주세요.",
                        )
                    }
                }
        }
    }

    fun retryOpenSession() = openSession()

    fun onDraftChanged(value: String) {
        // Editing the body abandons the pending retry: an edited message is a new message with a
        // new id, never the same id with different content.
        _state.update {
            if (it.pendingRetry != null) {
                app.pendingChatStore.clear()
                it.copy(draft = value, pendingRetry = null)
            } else {
                it.copy(draft = value)
            }
        }
    }

    fun onAutoReadChanged(enabled: Boolean) {
        if (!enabled) tts.stop()
        _state.update { it.copy(autoReadEnabled = enabled) }
    }

    fun onSpeak(message: ChatMessage) = tts.speak(message.id, message.text)

    fun onStopSpeaking() = tts.stop()

    fun send() {
        val current = _state.value
        if (!current.canSend) return
        val sessionId = current.sessionId ?: return
        val content = current.draft.trim()
        if (content.isEmpty()) return

        val clientMessageId = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                draft = "",
                isSending = true,
                errorMessage = null,
                messages = it.messages + ChatMessage(clientMessageId, fromUser = true, text = content),
            )
        }
        dispatch(sessionId, clientMessageId, content, INPUT_MODALITY_TEXT, isRetry = false)
    }

    /** One retry only, with the same id, an identical body and an identical modality. */
    fun retryPending() {
        val current = _state.value
        val pending = current.pendingRetry ?: return
        val sessionId = current.sessionId ?: return
        if (current.isSending) return

        _state.update { it.copy(isSending = true, pendingRetry = null, errorMessage = null) }
        dispatch(
            sessionId = sessionId,
            clientMessageId = pending.clientMessageId,
            content = pending.content,
            inputModality = pending.inputModality,
            isRetry = true,
        )
    }

    fun finishSession() {
        val sessionId = _state.value.sessionId ?: return
        tts.stop()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { sessionRepository.finish(sessionId) } }
            app.pendingChatStore.clear()
            _state.update { it.copy(finished = true) }
        }
    }

    fun stopPlaybackForNavigation() = tts.stop()

    private fun dispatch(
        sessionId: String,
        clientMessageId: String,
        content: String,
        inputModality: String,
        isRetry: Boolean,
    ) {
        val body = JSONObject()
            .put("clientMessageId", clientMessageId)
            .put("content", content)
            .put("inputModality", inputModality)
            .toString()

        viewModelScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    app.apiClient.execute(
                        ApiRequest("POST", app.endpoints.messages(sessionId), body = body),
                    )
                }
            }
            response
                .onSuccess { apiResponse ->
                    if (apiResponse.isSuccessful) {
                        app.pendingChatStore.clear()
                        applyAssistantReply(apiResponse)
                    } else {
                        applyFailure(apiResponse, clientMessageId, content, inputModality, isRetry)
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isSending = false,
                            errorMessage = "네트워크 상태를 확인하고 다시 시도해 주세요.",
                        )
                    }
                }
        }
    }

    private fun applyAssistantReply(response: ApiResponse) {
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        val text = json?.optString("assistantText")?.takeIf { it.isNotBlank() }
        val id = json?.optString("assistantMessageId")?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        if (text == null) {
            _state.update { it.copy(isSending = false) }
            return
        }
        val message = ChatMessage(id = id, fromUser = false, text = text)
        _state.update { it.copy(isSending = false, messages = it.messages + message) }
        if (_state.value.autoReadEnabled) tts.speak(message.id, message.text)
    }

    /**
     * The user's bubble stays exactly where it is. A retryable provider failure becomes a pending
     * retry through [ChatRetryPolicy]; anything else is a plain error with the bubble intact.
     */
    private fun applyFailure(
        response: ApiResponse,
        clientMessageId: String,
        content: String,
        inputModality: String,
        isRetry: Boolean,
    ) {
        val failure = parseDialogueFailure(response)
        val pending = if (isRetry) {
            null
        } else {
            ChatRetryPolicy.pending(failure, clientMessageId, content, inputModality)
        }

        if (pending != null) {
            app.pendingChatStore.save(pending.clientMessageId, pending.content, pending.inputModality)
        } else {
            // Success, retries exhausted, or a non-retryable failure — the pending body must not
            // outlive its usefulness on disk.
            app.pendingChatStore.clear()
        }

        _state.update {
            it.copy(
                isSending = false,
                pendingRetry = pending,
                errorMessage = if (pending != null) null else messageFor(response.statusCode),
            )
        }
    }

    private fun parseDialogueFailure(response: ApiResponse): DialogueFailure? {
        if (response.statusCode != HTTP_BAD_GATEWAY) return null
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        return DialogueFailure(
            code = json?.optString("code")?.takeIf { it.isNotBlank() } ?: "provider_failure",
            clientMessageId = json?.optString("clientMessageId")?.takeIf { it.isNotBlank() },
            userMessageId = json?.optString("userMessageId")?.takeIf { it.isNotBlank() },
            retryable = json?.optBoolean("retryable", true) ?: true,
            attemptsRemaining = json?.optInt("attemptsRemaining", 1) ?: 1,
        )
    }

    private fun restorePendingRetry() {
        val stored = app.pendingChatStore.load() ?: return
        val (clientMessageId, inputModality, content) = stored
        val pending = ChatRetryPolicy.pending(
            failure = DialogueFailure(
                code = "restored",
                clientMessageId = clientMessageId,
                userMessageId = null,
                retryable = true,
                attemptsRemaining = 1,
            ),
            clientMessageId = clientMessageId,
            content = content,
            inputModality = inputModality,
        ) ?: return
        _state.update {
            it.copy(
                pendingRetry = pending,
                messages = it.messages + ChatMessage(clientMessageId, fromUser = true, text = content),
            )
        }
    }

    /** Never surfaces a server address, a model path or a stack trace. */
    private fun messageFor(statusCode: Int): String = when (statusCode) {
        403 -> "설정에서 AI 분석 동의를 켜면 대화를 이어갈 수 있어요."
        404 -> "이 대화는 이미 종료되었어요. 홈에서 다시 시작해 주세요."
        409 -> "같은 메시지가 이미 전송됐어요."
        413, 415, 422 -> "메시지를 다시 확인해 주세요."
        502 -> "답변을 불러오지 못했어요."
        503 -> "대화 기능을 지금 사용할 수 없어요."
        504 -> "응답이 지연되고 있어요. 잠시 후 다시 시도해 주세요."
        else -> "잠시 후 다시 시도해 주세요."
    }

    override fun onCleared() {
        tts.shutdown()
        super.onCleared()
    }

    companion object {
        private const val HTTP_BAD_GATEWAY = 502
        private const val RESUMED_NOTICE = "이전 대화를 이어서 진행할게요."

        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(app) as T
            }
    }
}
