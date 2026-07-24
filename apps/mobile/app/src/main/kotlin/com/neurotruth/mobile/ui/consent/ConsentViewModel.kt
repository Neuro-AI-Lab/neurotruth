package com.neurotruth.mobile.ui.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ConsentSelection
import com.neurotruth.mobile.core.ConsentVersions
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.AuthenticationRequiredException
import com.neurotruth.mobile.core.net.PatientSignupRequest
import com.neurotruth.mobile.ui.auth.PendingSignup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ten consent booleans the server accepts, split the way NT-03 presents them. */
enum class ConsentKey(
    val label: String,
    val whenOn: String,
    val whenOff: String,
    val required: Boolean,
) {
    TOS("이용약관", "서비스 이용에 필요한 기본 약관입니다.", "", required = true),
    PRIVACY("개인정보", "개인정보 수집·이용에 대한 동의입니다.", "", required = true),
    SENSITIVE("민감정보", "건강 관련 민감정보 처리에 대한 동의입니다.", "", required = true),

    BIOSIGNAL(
        "생체신호",
        "Watch PPG·GSR 수집과 업로드를 사용해요.",
        "Watch 측정을 사용할 수 없어요.",
        required = false,
    ),
    AI_ANALYSIS(
        "AI 분석",
        "갈망 모델 추론과 세션 대화를 사용해요.",
        "센서 보관만 하고 갈망 결과는 표시하지 않아요.",
        required = false,
    ),
    NOTIFICATION(
        "알림",
        "갈망 알림과 대화 제안을 받아요.",
        "기록은 남지만 알림은 표시되지 않아요. 대화는 직접 열 수 있어요.",
        required = false,
    ),
    VOICE(
        "음성 입력",
        "음성으로 말한 내용을 글로 옮겨 입력할 수 있어요.",
        "텍스트 입력만 사용해요.",
        required = false,
    ),
    CAMERA_RPPG(
        "얼굴 측정",
        "카메라로 측정을 사용할 수 있어요.",
        "'카메라로 측정'이 사유와 함께 비활성화돼요.",
        required = false,
    ),
    FACE_VIDEO_RETENTION(
        "얼굴 영상 보관",
        "얼굴 영상 업로드와 서버 보관을 허용해요.",
        "얼굴 측정을 시작할 수 없어요.",
        required = false,
    ),
    REPORT_GENERATION(
        "리포트 생성",
        "세션 리포트를 생성해요.",
        "리포트를 생성하지 않아요.",
        required = false,
    ),
    ;

    companion object {
        val REQUIRED: List<ConsentKey> = entries.filter { it.required }
        val OPTIONAL: List<ConsentKey> = entries.filterNot { it.required }
    }
}

/** Optional consents start on except the two that carry facial video. */
private fun defaultConsentValues(): Map<ConsentKey, Boolean> =
    ConsentKey.entries.associateWith { key ->
        when (key) {
            ConsentKey.CAMERA_RPPG, ConsentKey.FACE_VIDEO_RETENTION -> false
            else -> true
        }
    }

data class ConsentUiState(
    val values: Map<ConsentKey, Boolean> = defaultConsentValues(),
    val isNewAccount: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,
) {
    val requiredSatisfied: Boolean
        get() = ConsentKey.REQUIRED.all { values[it] == true }

    val canSubmit: Boolean
        get() = requiredSatisfied && !isSubmitting
}

/**
 * NT-03 · 동의 · 권한.
 *
 * For a new user this is where the account is actually created: one
 * `POST /api/auth/patient/signup` carrying the NT-02 details plus the consent snapshot, because the
 * server requires consent on the signup body. For an existing account it appends an immutable
 * snapshot through `POST /api/me/consents`.
 *
 * The three version strings come from [ConsentVersions] in `:core` and are never retyped here.
 */
class ConsentViewModel(
    private val app: NeuroTruthApp,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ConsentUiState(isNewAccount = PendingSignup.peek() != null),
    )
    val state: StateFlow<ConsentUiState> = _state.asStateFlow()

    fun onToggle(key: ConsentKey, value: Boolean) {
        _state.update { current ->
            current.copy(
                values = current.values + (key to value),
                errorMessage = null,
            )
        }
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) {
            if (!current.requiredSatisfied) {
                _state.update { it.copy(errorMessage = "필수 동의 3가지를 모두 켜 주세요.") }
            }
            return
        }

        val selection = runCatching { current.toSelection() }.getOrElse {
            _state.update { s -> s.copy(errorMessage = "필수 동의 3가지를 모두 켜 주세요.") }
            return
        }
        val pending = PendingSignup.peek()

        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (pending != null) {
                        app.apiClient.signup(
                            PatientSignupRequest(
                                email = pending.email,
                                password = pending.password,
                                consent = selection,
                                name = pending.displayName,
                            ),
                        )
                    } else {
                        app.apiClient.appendConsent(selection)
                    }
                }
            }
            outcome
                .onSuccess {
                    // The password leaves memory the moment it is no longer needed.
                    PendingSignup.clear()
                    _state.update { it.copy(isSubmitting = false, saved = true) }
                }
                .onFailure { error ->
                    _state.update { it.copy(isSubmitting = false, errorMessage = messageFor(error)) }
                }
        }
    }

    private fun ConsentUiState.toSelection(): ConsentSelection = ConsentSelection(
        tos = values[ConsentKey.TOS] == true,
        privacy = values[ConsentKey.PRIVACY] == true,
        sensitive = values[ConsentKey.SENSITIVE] == true,
        biosignal = values[ConsentKey.BIOSIGNAL] == true,
        aiAnalysis = values[ConsentKey.AI_ANALYSIS] == true,
        notification = values[ConsentKey.NOTIFICATION] == true,
        reportGeneration = values[ConsentKey.REPORT_GENERATION] == true,
        voice = values[ConsentKey.VOICE] == true,
        cameraRppg = values[ConsentKey.CAMERA_RPPG] == true,
        faceVideoRetention = values[ConsentKey.FACE_VIDEO_RETENTION] == true,
    )

    private fun messageFor(error: Throwable): String = when {
        error is AuthenticationRequiredException -> "로그인이 필요해요. 다시 로그인해 주세요."
        error is ApiHttpException && error.statusCode == 409 ->
            "이미 사용 중인 이메일이에요."
        error is ApiHttpException && error.statusCode == 422 ->
            "입력 내용을 다시 확인해 주세요."
        error is ApiHttpException && error.statusCode in 500..599 ->
            "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
        else -> "저장하지 못했어요. 네트워크 상태를 확인하고 다시 시도해 주세요."
    }

    companion object {
        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ConsentViewModel(app) as T
            }
    }
}
