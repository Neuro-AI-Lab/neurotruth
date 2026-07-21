package com.example.healthsensor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

sealed interface PatientAuthState {
    data object Restoring : PatientAuthState
    data object SignedOut : PatientAuthState
    data class MustChangePassword(val user: AuthUser) : PatientAuthState
    data class Authenticated(val user: AuthUser) : PatientAuthState
}

object AuthInputPolicy {
    fun canLogin(email: String, password: String): Boolean =
        email.trim().contains('@') && password.isNotBlank()

    fun canSignup(
        email: String,
        password: String,
        tos: Boolean,
        privacy: Boolean,
        sensitive: Boolean
    ): Boolean = canLogin(email, password) && password.length >= 12 && tos && privacy && sensitive

    fun apiBaseUrl(config: ServerConfig): String {
        return config.apiBaseUrl()
    }
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val apiClient = MobileApiProvider.get(application)

    private val _state = MutableStateFlow<PatientAuthState>(PatientAuthState.Restoring)
    val state: StateFlow<PatientAuthState> = _state

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    init {
        restoreSession()
        viewModelScope.launch {
            MobileAuthRuntime.state.collect { runtime ->
                if (!runtime.authenticated && _state.value !is PatientAuthState.Restoring) {
                    _state.value = PatientAuthState.SignedOut
                }
            }
        }
    }

    fun login(email: String, password: String) {
        if (_isLoading.value || !AuthInputPolicy.canLogin(email, password)) {
            _message.value = "이메일과 비밀번호를 확인해 주세요."
            return
        }
        runAuth("로그인 중", consent = null) {
            apiClient.login(LoginRequest(email, password))
        }
    }

    fun signup(
        email: String,
        password: String,
        name: String?,
        consent: ConsentSelection
    ) {
        if (_isLoading.value || !AuthInputPolicy.canSignup(
                email,
                password,
                consent.tos,
                consent.privacy,
                consent.sensitive
            )
        ) {
            _message.value = "필수 동의와 12자 이상의 비밀번호를 확인해 주세요."
            return
        }
        runAuth("가입 중", consent = consent) {
            apiClient.signupPatient(
                PatientSignupRequest(
                    email = email,
                    password = password,
                    name = name,
                    consent = consent
                )
            )
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) {
        (_state.value as? PatientAuthState.MustChangePassword)?.user ?: return
        if (_isLoading.value || currentPassword.isBlank() || newPassword.length < 12 || newPassword != confirmation) {
            _message.value = "현재 비밀번호와 일치하는 12자 이상의 새 비밀번호를 입력해 주세요."
            return
        }
        _isLoading.value = true
        _message.value = "비밀번호 변경 중"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val response = apiClient.executeAuthenticated(
                        ApiRequest(
                            method = "POST",
                            url = apiClient.endpoints.changePassword,
                            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                            body = JSONObject()
                                .put("currentPassword", currentPassword)
                                .put("newPassword", newPassword)
                                .toString()
                        )
                    )
                    if (response.statusCode !in 200..299) throw ApiHttpException(response.statusCode)
                }
            }.onSuccess {
                CredentialRevocationCleanupPolicy.execute(
                    stopMonitoring = {
                        PhoneMonitoringState.isUploadEnabled.value = false
                        PhoneMonitoringState.isPredictionReceiverEnabled.value = false
                        PhoneMonitoringService.stop(getApplication())
                    },
                    clearConversationSessions = {
                        SharedPreferencesConversationSessionStore(getApplication()).clear()
                    },
                    clearCredentials = apiClient::clearSession,
                    clearRuntime = MobileAuthRuntime::signOut
                )
                _state.value = PatientAuthState.SignedOut
                _message.value = "비밀번호가 변경되었습니다. 새 비밀번호로 다시 로그인해 주세요."
            }.onFailure(::showFailure)
            _isLoading.value = false
        }
    }

    fun updateConsent(consent: ConsentSelection) {
        val user = (_state.value as? PatientAuthState.Authenticated)?.user ?: return
        if (_isLoading.value) return
        _isLoading.value = true
        _message.value = "동의 설정 저장 중"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { apiClient.appendConsent(consent) }
            }.onSuccess { saved ->
                MobileAuthRuntime.accept(getApplication(), user, saved)
                _message.value = "동의 설정이 저장되었습니다. 철회한 항목은 신규 수집부터 즉시 중지됩니다."
            }.onFailure(::showFailure)
            _isLoading.value = false
        }
    }

    fun clearMessage() {
        if (!_isLoading.value) _message.value = ""
    }

    fun logout() {
        if (_isLoading.value) return
        _state.value = PatientAuthState.SignedOut
        _message.value = ""
        SharedPreferencesConversationSessionStore(getApplication()).clear()
        LogoutCleanupPolicy.execute(
            stopMonitoring = {
                PhoneMonitoringState.isUploadEnabled.value = false
                PhoneMonitoringState.isPredictionReceiverEnabled.value = false
                PhoneMonitoringService.stop(getApplication())
            },
            clearRuntime = MobileAuthRuntime::signOut
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { apiClient.logout() }
        }
    }

    private fun restoreSession() {
        viewModelScope.launch {
            val auth = runCatching { withContext(Dispatchers.IO) { apiClient.recoverSession() } }
                .getOrNull()
            if (auth == null) {
                apiClient.clearSession()
                _state.value = PatientAuthState.SignedOut
            } else {
                accept(auth.user, auth.consent)
            }
        }
    }

    private fun runAuth(
        progressMessage: String,
        consent: ConsentSelection?,
        request: () -> AuthTokens
    ) {
        _isLoading.value = true
        _message.value = progressMessage
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { request() } }
                .onSuccess {
                    accept(it.user, consent ?: it.consent)
                    _message.value = ""
                }
                .onFailure(::showFailure)
            _isLoading.value = false
        }
    }

    private fun accept(user: AuthUser, consent: ConsentSelection? = null) {
        MobileAuthRuntime.accept(getApplication(), user, consent)
        _state.value = if (user.mustChangePassword) {
            PatientAuthState.MustChangePassword(user)
        } else {
            PatientAuthState.Authenticated(user)
        }
    }

    private fun showFailure(error: Throwable) {
        _message.value = when (error) {
            is ApiHttpException -> "요청에 실패했습니다. (HTTP ${error.statusCode})"
            else -> "서버 연결을 확인해 주세요."
        }
    }
}
