package com.neurotruth.mobile.ui.auq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.Auq
import com.neurotruth.mobile.core.AuqChoice
import com.neurotruth.mobile.core.AuqPayload
import com.neurotruth.mobile.core.AuqQuestion
import com.neurotruth.mobile.core.AuqSubmissionPolicy
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.data.ActiveSessionStore
import com.neurotruth.mobile.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AuqUiState(
    val index: Int = 0,
    val responses: Map<Int, Int> = emptyMap(),
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val submitted: Boolean = false,
    val resultScore: Int? = null,
    val skipped: Boolean = false,
) {
    val question: AuqQuestion get() = Auq.QUESTIONS[index]

    /** `1 / 8`. The item number is progress, never a score shown to the user. */
    val progressLabel: String get() = "${index + 1} / ${Auq.ITEM_COUNT}"

    val progressFraction: Float get() = (index + 1).toFloat() / Auq.ITEM_COUNT

    val selectedResponse: Int? get() = responses[question.number]

    val isFirstItem: Boolean get() = index == 0

    val isLastItem: Boolean get() = index == Auq.ITEM_COUNT - 1

    val canAdvance: Boolean get() = selectedResponse != null && !isSubmitting

    val allAnswered: Boolean
        get() = Auq.QUESTIONS.all { responses[it.number] != null }

    val canSubmit: Boolean get() = allAnswered && !isSubmitting

    /** Skip goes directly to Chat; a persisted result remains visible until explicit continuation. */
    val shouldOpenChat: Boolean get() = skipped
}

/**
 * NT-06 · 자기설문 (AUQ).
 *
 * Every rule lives in `:core`: the items and the seven sentence labels in [Auq], the "does this post
 * anything at all" decision and the attempt numbering in [AuqSubmissionPolicy], and the request body
 * in [AuqPayload]. Nothing here retypes a label or hand-rolls the JSON, so `phase` and `attemptNo`
 * can never be dropped.
 *
 * The endpoint carries no idempotency key, unlike messages, sensor windows and rPPG jobs. A blind
 * retry after a timeout writes two rows for one episode and corrupts the NT-08 average, so an
 * ambiguous outcome is treated as failed ([AuqSubmissionPolicy.AUTO_RETRY_ALLOWED] is false), skip
 * stays available, and only an explicit user resubmit advances the attempt number.
 */
class AuqViewModel(
    private val app: NeuroTruthApp,
) : ViewModel() {

    private val sessionRepository = SessionRepository(
        client = app.apiClient,
        endpoints = app.endpoints,
        activeSessionStore = ActiveSessionStore(app),
    )

    /** Advanced only by an explicit resubmit; an ambiguous outcome may already have written a row. */
    private var lastAttemptNo: Int? = null

    private val _state = MutableStateFlow(AuqUiState())
    val state: StateFlow<AuqUiState> = _state.asStateFlow()

    fun onResponseSelected(value: Int) {
        if (!Auq.isValidResponse(value)) return
        _state.update { current ->
            current.copy(
                responses = current.responses + (current.question.number to value),
                errorMessage = null,
            )
        }
    }

    fun onNext() {
        _state.update { current ->
            if (!current.canAdvance || current.isLastItem) current
            else current.copy(index = current.index + 1, errorMessage = null)
        }
    }

    fun onPrevious() {
        _state.update { current ->
            if (current.isFirstItem || current.isSubmitting) current
            else current.copy(index = current.index - 1, errorMessage = null)
        }
    }

    /** `[건너뛰고 대화하기]` posts nothing at all — not even a placeholder request. */
    fun onSkip() {
        if (AuqSubmissionPolicy.shouldPostAssessment(AuqChoice.SKIP)) return
        _state.update { it.copy(skipped = true, errorMessage = null) }
    }

    fun onSubmit() {
        val current = _state.value
        if (!current.canSubmit) {
            _state.update { it.copy(errorMessage = "문항 ${Auq.ITEM_COUNT}개에 모두 답해 주세요.") }
            return
        }
        if (!AuqSubmissionPolicy.shouldPostAssessment(AuqChoice.COMPLETE)) return

        val sessionId = sessionRepository.activeSessionId()
        if (sessionId == null) {
            _state.update {
                it.copy(errorMessage = "대화 정보를 찾지 못했어요. 건너뛰고 대화를 시작할 수 있어요.")
            }
            return
        }

        val result = Auq.buildResult(current.responses, System.currentTimeMillis())
        if (result == null) {
            _state.update { it.copy(errorMessage = "문항 ${Auq.ITEM_COUNT}개에 모두 답해 주세요.") }
            return
        }

        val attemptNo = AuqSubmissionPolicy.nextAttemptNo(lastAttemptNo)
        // Recorded before the call: an ambiguous outcome may already have written this attempt, so a
        // resubmit must never reuse the number.
        lastAttemptNo = attemptNo
        val body = runCatching { AuqPayload.toJson(result, attemptNo) }.getOrElse {
            _state.update { s -> s.copy(errorMessage = "응답을 다시 확인해 주세요.") }
            return
        }

        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    app.apiClient.execute(
                        ApiRequest("POST", app.endpoints.assessments(sessionId), body = body),
                    )
                }
            }
            outcome
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                submitted = true,
                                resultScore = result.totalScore,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(isSubmitting = false, errorMessage = messageFor(response.statusCode))
                        }
                    }
                }
                .onFailure {
                    // Ambiguous: the write may or may not have landed. No auto-retry.
                    _state.update {
                        it.copy(isSubmitting = false, errorMessage = AMBIGUOUS_OUTCOME_MESSAGE)
                    }
                }
        }
    }

    /** Never surfaces a server address, a status code or a stack trace. */
    private fun messageFor(statusCode: Int): String = when (statusCode) {
        403 -> "설정에서 동의를 확인하면 응답을 저장할 수 있어요."
        404 -> "이 대화는 이미 종료되었어요. 건너뛰고 대화를 시작할 수 있어요."
        422 -> "응답을 다시 확인해 주세요."
        in 500..599 -> "저장하지 못했어요. 다시 보내거나 건너뛰고 대화할 수 있어요."
        else -> "저장하지 못했어요. 다시 보내거나 건너뛰고 대화할 수 있어요."
    }

    companion object {
        private const val AMBIGUOUS_OUTCOME_MESSAGE =
            "전송 결과를 확인하지 못했어요. 다시 보내거나 건너뛰고 대화할 수 있어요."

        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AuqViewModel(app) as T
            }
    }
}
