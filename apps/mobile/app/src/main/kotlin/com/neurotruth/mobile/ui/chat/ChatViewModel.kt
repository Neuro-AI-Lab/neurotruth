package com.neurotruth.mobile.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ChatRetryPolicy
import com.neurotruth.mobile.core.ConsentGates
import com.neurotruth.mobile.core.DialogueFailure
import com.neurotruth.mobile.core.INPUT_MODALITY_TEXT
import com.neurotruth.mobile.core.INPUT_MODALITY_VOICE
import com.neurotruth.mobile.core.PendingChatRetry
import com.neurotruth.mobile.data.AlertSessionEntry
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.ApiResponse
import com.neurotruth.mobile.data.ActiveSessionStore
import com.neurotruth.mobile.data.ProfileRepository
import com.neurotruth.mobile.data.SessionRepository
import com.neurotruth.mobile.data.TranscriptionRepository
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

/**
 * FastAPI serializes HTTPException payloads under `detail`. Accept the unwrapped shape as well so
 * fake transports and older servers remain compatible.
 */
internal fun dialogueFailure(response: ApiResponse): DialogueFailure? {
    if (response.statusCode != 502) return null
    val root = runCatching { JSONObject(response.body) }.getOrNull()
    val json = root?.optJSONObject("detail") ?: root
    return DialogueFailure(
        code = json?.optString("code")?.takeIf { it.isNotBlank() } ?: "provider_failure",
        clientMessageId = json?.optString("clientMessageId")?.takeIf { it.isNotBlank() },
        userMessageId = json?.optString("userMessageId")?.takeIf { it.isNotBlank() },
        retryable = json?.optBoolean("retryable", true) ?: true,
        attemptsRemaining = json?.optInt("attemptsRemaining", 1) ?: 1,
    )
}

/** A voice reply is read once even when the global text-reply switch is off. */
internal fun shouldAutoReadReply(inputModality: String, autoReadEnabled: Boolean): Boolean =
    inputModality == INPUT_MODALITY_VOICE || autoReadEnabled

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
    val isFinishing: Boolean = false,
    val pendingRetry: PendingChatRetry? = null,
    val speakingMessageId: String? = null,
    val ttsAvailable: Boolean = false,
    /** Typed drafts are always text; STT bypasses this field and dispatches a separate voice bubble. */
    val draftInputModality: String = INPUT_MODALITY_TEXT,
    val voiceConsentGranted: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val voiceNotice: String? = null,
    /**
     * Set when the server created a new session, so NT-06 is offered before dialogue. Returning
     * from the AUQ re-enters this screen, where the server hands back the same active session with
     * a null `assistantText` — so the flag is false the second time and there is no loop.
     */
    val requiresAuq: Boolean = false,
    val errorMessage: String? = null,
    val finished: Boolean = false,
) {
    val canSend: Boolean
        get() = sessionId != null && !isSending && !isFinishing && !isPreparing && draft.isNotBlank() &&
            pendingRetry == null && !isRecording && !isTranscribing

    /**
     * The microphone is an addition to text input, never a replacement: it is simply absent when
     * voice consent is missing, and typing stays available in every state below.
     */
    val micEnabled: Boolean
        get() = voiceConsentGranted && sessionId != null && !isPreparing && !isSending && !isFinishing &&
            !isTranscribing
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

    private val transcriptionRepository = TranscriptionRepository(app.apiClient, app.endpoints)

    private val profileRepository = ProfileRepository(app.apiClient, app.endpoints)

    private val recorder = VoiceRecorder(app)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** One voice-correlated response waiting for the device TTS engine to finish initialization. */
    private var pendingAutoReadMessage: ChatMessage? = null

    private val tts = ChatTtsController(
        context = app,
        onSpeakingChanged = { id -> _state.update { it.copy(speakingMessageId = id) } },
        onReady = { available ->
            _state.update { it.copy(ttsAvailable = available) }
            if (available) playPendingAutoRead()
        },
    )

    init {
        recorder.onMaxDurationReached = {
            // The 30-second cap is enforced by the platform; finalize exactly as a tap would.
            viewModelScope.launch { stopAndTranscribe() }
        }
        openSession()
        loadVoiceConsent()
    }

    /**
     * PRD §5.3. The 챗봇 tab is a `manual_checkin`; the entry point, not this call site, carries the
     * type. A newly created session routes through AUQ, while an active one resumes dialogue.
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
                            requiresAuq = entry.requiresAuq,
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

    /** Consumed once the navigation to NT-06 has happened, so it cannot fire twice. */
    fun onAuqNavigated() {
        _state.update { it.copy(requiresAuq = false) }
    }

    /**
     * Consumed once the finish navigation has happened. Without this the flag stays true, and because
     * the 챗봇 tab preserves its ViewModel across tab switches (saveState/restoreState), returning to
     * the tab would re-fire the finish effect and bounce the user straight back out.
     */
    fun onFinishedNavigated() {
        _state.update { it.copy(finished = false) }
    }

    fun onDraftChanged(value: String) {
        // Editing the body abandons the pending retry: an edited message is a new message with a
        // new id, never the same id with different content.
        _state.update {
            if (it.pendingRetry != null) {
                app.pendingChatStore.clear()
                it.copy(draft = value, draftInputModality = INPUT_MODALITY_TEXT, pendingRetry = null)
            } else {
                it.copy(draft = value, draftInputModality = INPUT_MODALITY_TEXT)
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
        val inputModality = INPUT_MODALITY_TEXT
        _state.update {
            it.copy(
                draft = "",
                draftInputModality = INPUT_MODALITY_TEXT,
                isSending = true,
                errorMessage = null,
                voiceNotice = null,
                messages = it.messages + ChatMessage(clientMessageId, fromUser = true, text = content),
            )
        }
        dispatch(sessionId, clientMessageId, content, inputModality, isRetry = false)
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
        if (_state.value.isFinishing) return
        tts.stop()
        _state.update { it.copy(isFinishing = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { sessionRepository.finish(sessionId) }
            }
            outcome
                .onSuccess {
                    app.pendingChatStore.clear()
                    _state.update { it.copy(isFinishing = false, finished = true) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isFinishing = false,
                            errorMessage = "대화를 종료하지 못했어요. 잠시 후 다시 시도해 주세요.",
                        )
                    }
                }
        }
    }

    fun stopPlaybackForNavigation() {
        pendingAutoReadMessage = null
        tts.stop()
    }

    // -----------------------------------------------------------------------------------------
    // NT-07 STT
    // -----------------------------------------------------------------------------------------

    /**
     * `voice` consent gates the microphone. The snapshot the auth client already holds is used when
     * present so the screen does not wait on the network; otherwise `GET /api/me` fills it in. A
     * failure here leaves the microphone hidden and text input untouched.
     */
    private fun loadVoiceConsent() {
        val cached = ConsentGates(app.apiClient.consent())
        if (cached.canUseVoice) {
            _state.update { it.copy(voiceConsentGranted = true) }
            return
        }
        viewModelScope.launch {
            val granted = withContext(Dispatchers.IO) {
                runCatching { ConsentGates(profileRepository.me().consent).canUseVoice }
                    .getOrDefault(false)
            }
            _state.update { it.copy(voiceConsentGranted = granted) }
        }
    }

    fun onMicPermissionChanged(granted: Boolean) {
        _state.update { it.copy(micPermissionGranted = granted) }
    }

    /** Denying the microphone leaves text conversation fully functional; it only adds a notice. */
    fun onMicPermissionDenied() {
        _state.update { it.copy(micPermissionGranted = false, voiceNotice = MIC_PERMISSION_NOTICE) }
    }

    /** Tap to start, tap again to stop and transcribe. */
    fun onMicToggled() {
        if (_state.value.isRecording) {
            viewModelScope.launch { stopAndTranscribe() }
            return
        }
        val current = _state.value
        if (!current.micEnabled || !current.micPermissionGranted) return
        if (!recorder.start()) {
            _state.update { it.copy(voiceNotice = RECORD_FAILED_NOTICE) }
            return
        }
        _state.update {
            it.copy(isRecording = true, voiceNotice = null, errorMessage = null)
        }
    }

    /** Discards the capture without uploading it; the temporary file is deleted here. */
    fun cancelRecording() {
        if (!_state.value.isRecording) return
        recorder.cancel()
        _state.update { it.copy(isRecording = false, voiceNotice = null) }
    }

    /** Leaving the screen must not leave audio on disk. */
    fun releaseRecording() {
        recorder.cancel()
        _state.update { it.copy(isRecording = false) }
    }

    private suspend fun stopAndTranscribe() {
        if (!recorder.isRecording) return
        val file = recorder.stop()
        _state.update { it.copy(isRecording = false) }
        val sessionId = _state.value.sessionId
        if (file == null || sessionId == null) {
            file?.delete()
            _state.update { it.copy(voiceNotice = RECORD_FAILED_NOTICE) }
            return
        }

        _state.update { it.copy(isTranscribing = true, voiceNotice = null) }
        val outcome = withContext(Dispatchers.IO) {
            try {
                runCatching { transcriptionRepository.transcribe(sessionId, file) }
            } finally {
                // Deleted on success, failure and cancellation alike.
                file.delete()
            }
        }
        outcome
            .onSuccess { text ->
                if (text.isNullOrBlank()) {
                    _state.update {
                        it.copy(isTranscribing = false, voiceNotice = EMPTY_TRANSCRIPT_NOTICE)
                    }
                    return@onSuccess
                }
                val content = text.trim()
                val clientMessageId = UUID.randomUUID().toString()
                // The existing typed draft is deliberately untouched. STT creates its own voice
                // bubble and follows the same idempotent one-retry route as a typed message.
                _state.update {
                    it.copy(
                        isTranscribing = false,
                        isSending = true,
                        voiceNotice = VOICE_SENT_NOTICE,
                        errorMessage = null,
                        messages = it.messages + ChatMessage(
                            id = clientMessageId,
                            fromUser = true,
                            text = content,
                        ),
                    )
                }
                dispatch(
                    sessionId = sessionId,
                    clientMessageId = clientMessageId,
                    content = content,
                    inputModality = INPUT_MODALITY_VOICE,
                    isRetry = false,
                )
            }
            .onFailure { error ->
                _state.update {
                    it.copy(isTranscribing = false, voiceNotice = voiceNoticeFor(error))
                }
            }
    }

    /** STT never blocks the conversation: every branch leaves the text field usable. */
    private fun voiceNoticeFor(error: Throwable): String = when {
        error !is ApiHttpException -> "네트워크 상태를 확인하고 다시 시도해 주세요. 직접 입력해도 괜찮아요."
        error.statusCode == 403 -> "설정에서 음성 동의를 켜면 마이크를 사용할 수 있어요."
        error.statusCode == 404 -> "이 대화는 이미 종료되었어요."
        error.statusCode == 413 || error.statusCode == 415 || error.statusCode == 422 ->
            "녹음을 인식할 수 없었어요. 직접 입력해 주세요."
        error.statusCode == 503 -> "음성 인식을 지금 사용할 수 없어요. 직접 입력해 주세요."
        error.statusCode == 504 -> "음성 인식이 지연되고 있어요. 직접 입력해 주세요."
        else -> "음성을 인식하지 못했어요. 직접 입력해 주세요."
    }

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
                        applyAssistantReply(apiResponse, inputModality)
                    } else {
                        applyFailure(apiResponse, clientMessageId, content, inputModality, isRetry)
                    }
                }
                .onFailure {
                    val pending = if (isRetry) {
                        null
                    } else {
                        ChatRetryPolicy.pending(
                            failure = DialogueFailure(
                                code = "network_failure",
                                clientMessageId = clientMessageId,
                                userMessageId = null,
                                retryable = true,
                                attemptsRemaining = 1,
                            ),
                            clientMessageId = clientMessageId,
                            content = content,
                            inputModality = inputModality,
                        )
                    }
                    if (pending != null) {
                        app.pendingChatStore.save(
                            pending.clientMessageId,
                            pending.content,
                            pending.inputModality,
                        )
                    }
                    _state.update {
                        it.copy(
                            isSending = false,
                            pendingRetry = pending,
                            voiceNotice = if (inputModality == INPUT_MODALITY_VOICE) null else it.voiceNotice,
                            errorMessage = if (pending == null) {
                                "네트워크 상태를 확인하고 다시 시도해 주세요."
                            } else {
                                null
                            },
                        )
                    }
                }
        }
    }

    private fun applyAssistantReply(response: ApiResponse, inputModality: String) {
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        val text = json?.optString("assistantText")?.takeIf { it.isNotBlank() }
        val id = json?.optString("assistantMessageId")?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        if (text == null) {
            _state.update { it.copy(isSending = false) }
            return
        }
        val message = ChatMessage(id = id, fromUser = false, text = text)
        _state.update {
            it.copy(
                isSending = false,
                voiceNotice = if (inputModality == INPUT_MODALITY_VOICE) null else it.voiceNotice,
                messages = it.messages + message,
            )
        }
        if (shouldAutoReadReply(inputModality, _state.value.autoReadEnabled)) {
            if (_state.value.ttsAvailable) {
                tts.speak(message.id, message.text)
            } else if (inputModality == INPUT_MODALITY_VOICE) {
                pendingAutoReadMessage = message
            }
        }
    }

    private fun playPendingAutoRead() {
        val message = pendingAutoReadMessage ?: return
        pendingAutoReadMessage = null
        tts.speak(message.id, message.text)
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
        val failure = dialogueFailure(response)
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
                voiceNotice = if (inputModality == INPUT_MODALITY_VOICE) null else it.voiceNotice,
                errorMessage = if (pending != null) null else messageFor(response.statusCode),
            )
        }
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
        pendingAutoReadMessage = null
        tts.shutdown()
        recorder.cancel()
        super.onCleared()
    }

    companion object {
        private const val RESUMED_NOTICE = "이전 대화를 이어서 진행할게요."
        private const val MIC_PERMISSION_NOTICE =
            "마이크 권한이 없어 음성 입력을 사용할 수 없어요. 직접 입력해도 괜찮아요."
        private const val RECORD_FAILED_NOTICE = "녹음을 시작하지 못했어요. 직접 입력해 주세요."
        private const val EMPTY_TRANSCRIPT_NOTICE = "음성을 인식하지 못했어요. 직접 입력해 주세요."
        private const val VOICE_SENT_NOTICE = "인식한 음성 메시지를 바로 전송했어요."

        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(app) as T
            }
    }
}
