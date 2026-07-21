package com.neurotruth.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.AuthenticationRequiredException
import com.neurotruth.mobile.core.net.PatientSignupRequest
import com.neurotruth.mobile.data.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The account details captured on NT-02, held in memory only for the duration of the signup flow.
 *
 * PRD §NT-02: a new user's details never reach the server from NT-02 — signup is a single call made
 * at NT-03 carrying the consent snapshot, because the server requires consent on the signup body.
 * The password is therefore parked here and nowhere else: not in preferences, not in the Keystore
 * store, not in a saved-state handle, not in a log.
 */
object PendingSignup {

    data class Credentials(
        val email: String,
        val password: String,
        val displayName: String?,
    )

    @Volatile
    private var credentials: Credentials? = null

    @Synchronized
    fun put(email: String, password: String, displayName: String?) {
        credentials = Credentials(
            email = email.trim(),
            password = password,
            displayName = displayName?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    @Synchronized
    fun peek(): Credentials? = credentials

    @Synchronized
    fun clear() {
        credentials = null
    }
}

enum class AuthMode { SIGNUP, LOGIN }

/** Where NT-02 hands control next. Consumed once, then cleared. */
enum class AuthDestination { CONSENT, HOME }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGNUP,
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    val emailLooksValid: Boolean
        get() = email.trim().let { it.length >= 3 && it.contains('@') && !it.startsWith('@') }

    val passwordLongEnough: Boolean
        get() = password.length >= PatientSignupRequest.MIN_PASSWORD_LENGTH

    val passwordsMatch: Boolean
        get() = password == confirmPassword

    val canSubmit: Boolean
        get() = !isSubmitting && emailLooksValid && passwordLongEnough &&
            (mode == AuthMode.LOGIN || passwordsMatch)
}

/**
 * NT-02 · 가입 · 로그인.
 *
 * Signup performs **no** network call — it validates client-side (the 12-character minimum is the
 * server contract, checked here to avoid a round trip) and hands the credentials to NT-03.
 *
 * Login calls the server directly, then reads `GET /api/me` to decide whether the stored consent
 * snapshot still satisfies the shipped versions. 401 handling lives in [AuthenticatedApiClient] and
 * is not reimplemented here.
 */
class AuthViewModel(
    private val app: NeuroTruthApp,
) : ViewModel() {

    private val profileRepository = ProfileRepository(app.apiClient, app.endpoints)

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _destination = MutableStateFlow<AuthDestination?>(null)
    val destination: StateFlow<AuthDestination?> = _destination.asStateFlow()

    fun onModeChanged(mode: AuthMode) =
        _state.update { it.copy(mode = mode, errorMessage = null) }

    fun onDisplayNameChanged(value: String) =
        _state.update { it.copy(displayName = value, errorMessage = null) }

    fun onEmailChanged(value: String) =
        _state.update { it.copy(email = value, errorMessage = null) }

    fun onPasswordChanged(value: String) =
        _state.update { it.copy(password = value, errorMessage = null) }

    fun onConfirmPasswordChanged(value: String) =
        _state.update { it.copy(confirmPassword = value, errorMessage = null) }

    fun consumeDestination() {
        _destination.value = null
    }

    /** NT-02 → NT-03. No server call: the account is created at NT-03 with the consent snapshot. */
    fun submitSignup() {
        val current = _state.value
        val failure = signupValidationError(current)
        if (failure != null) {
            _state.update { it.copy(errorMessage = failure) }
            return
        }
        PendingSignup.put(
            email = current.email,
            password = current.password,
            displayName = current.displayName,
        )
        _state.update { it.copy(errorMessage = null) }
        _destination.value = AuthDestination.CONSENT
    }

    fun submitLogin() {
        val current = _state.value
        if (!current.emailLooksValid) {
            _state.update { it.copy(errorMessage = ERROR_EMAIL) }
            return
        }
        if (current.password.isBlank()) {
            _state.update { it.copy(errorMessage = ERROR_PASSWORD_EMPTY) }
            return
        }
        if (current.isSubmitting) return

        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    app.apiClient.login(current.email.trim(), current.password)
                    val snapshot = profileRepository.me()
                    if (profileRepository.requiresConsent(snapshot)) {
                        AuthDestination.CONSENT
                    } else {
                        AuthDestination.HOME
                    }
                }
            }
            outcome
                .onSuccess { destination ->
                    PendingSignup.clear()
                    _state.update {
                        it.copy(isSubmitting = false, password = "", confirmPassword = "")
                    }
                    _destination.value = destination
                }
                .onFailure { error ->
                    _state.update { it.copy(isSubmitting = false, errorMessage = messageFor(error)) }
                }
        }
    }

    private fun signupValidationError(state: AuthUiState): String? = when {
        !state.emailLooksValid -> ERROR_EMAIL
        !state.passwordLongEnough ->
            "비밀번호는 ${PatientSignupRequest.MIN_PASSWORD_LENGTH}자 이상이어야 해요."
        !state.passwordsMatch -> "비밀번호가 서로 달라요."
        else -> null
    }

    /** Never surfaces a server address, a stack trace or a raw body. */
    private fun messageFor(error: Throwable): String = when {
        error is AuthenticationRequiredException -> "이메일 또는 비밀번호를 다시 확인해 주세요."
        error is ApiHttpException && error.statusCode == 401 ->
            "이메일 또는 비밀번호를 다시 확인해 주세요."
        error is ApiHttpException && error.statusCode == 429 ->
            "잠시 후 다시 시도해 주세요."
        error is ApiHttpException && error.statusCode in 500..599 ->
            "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
        else -> "연결에 실패했어요. 네트워크 상태를 확인해 주세요."
    }

    companion object {
        private const val ERROR_EMAIL = "이메일 주소를 다시 확인해 주세요."
        private const val ERROR_PASSWORD_EMPTY = "비밀번호를 입력해 주세요."

        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AuthViewModel(app) as T
            }
    }
}
