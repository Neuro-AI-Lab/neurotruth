package com.neurotruth.mobile.ui.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ConsentSelection
import com.neurotruth.mobile.core.ConsentVersions
import com.neurotruth.mobile.core.PRODUCT_NOTICE_VERSION
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.AuthenticationRequiredException
import com.neurotruth.mobile.core.net.PatientSignupRequest
import com.neurotruth.mobile.data.ActiveSessionStore
import com.neurotruth.mobile.data.ProfileRepository
import com.neurotruth.mobile.data.SessionRepository
import com.neurotruth.mobile.service.MonitoringService
import com.neurotruth.mobile.ui.consent.ConsentKey
import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The three device permissions NT-09 reports. Consent is not a permission and is listed apart. */
enum class DevicePermission(val label: String, val purpose: String) {
    NOTIFICATION("알림", "갈망 알림과 대화 제안을 받을 때 사용해요."),
    MICROPHONE("마이크", "대화에서 음성으로 입력할 때 사용해요."),
    CAMERA("카메라", "카메라로 측정할 때 사용해요."),
}

data class SettingsUiState(
    val isLoading: Boolean = true,
    val email: String = "",
    val displayName: String = "",
    val nameDraft: String = "",
    val isSavingName: Boolean = false,
    val nameSavedMessage: String? = null,

    val consentValues: Map<ConsentKey, Boolean> = emptyMap(),
    val isSavingConsent: Boolean = false,
    val consentSavedMessage: String? = null,

    val permissions: Map<DevicePermission, Boolean> = emptyMap(),

    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val isChangingPassword: Boolean = false,
    /** Field-level, on 현재 비밀번호 only: a wrong current password is not a screen-level failure. */
    val currentPasswordError: String? = null,
    val passwordChangedMessage: String? = null,

    val isSigningOut: Boolean = false,
    val signedOut: Boolean = false,
    val errorMessage: String? = null,
) {
    val nameChanged: Boolean
        get() = nameDraft.trim() != displayName.trim()

    val canSaveName: Boolean
        get() = !isSavingName && !isLoading && nameChanged && nameDraft.trim().length <= MAX_NAME_LENGTH

    val newPasswordLongEnough: Boolean
        get() = newPassword.length >= PatientSignupRequest.MIN_PASSWORD_LENGTH

    val newPasswordsMatch: Boolean
        get() = newPassword == confirmPassword

    val canChangePassword: Boolean
        get() = !isChangingPassword && currentPassword.isNotBlank() &&
            newPasswordLongEnough && newPasswordsMatch

    val consentVersionSummary: String
        get() = "약관 ${ConsentVersions.TOS} · 개인정보 ${ConsentVersions.PRIVACY} · " +
            "동의서 ${ConsentVersions.CONSENT_FORM}"

    val noticeVersion: String get() = PRODUCT_NOTICE_VERSION

    fun isGranted(permission: DevicePermission): Boolean = permissions[permission] == true

    private companion object {
        const val MAX_NAME_LENGTH = 200
    }
}

/**
 * NT-09 · 설정.
 *
 * Consent is append-only: a change writes a **new** immutable snapshot through
 * `POST /api/me/consents` ([AuthenticatedApiClient.appendConsent]) and never overwrites the previous
 * one. Withdrawing an optional consent blocks new processing; it does not delete anything already
 * collected, and the screen says so.
 *
 * Logout tears down everything the PRD lists: the server refresh token, the monitoring service
 * (which owns the SSE connection and the Watch upload loop), the active session id, the pending chat
 * body, and every per-user cache on disk. 401 handling stays in [AuthenticatedApiClient].
 */
class SettingsViewModel(
    private val app: NeuroTruthApp,
) : ViewModel() {

    private val profileRepository = ProfileRepository(app.apiClient, app.endpoints)
    private val sessionRepository = SessionRepository(
        client = app.apiClient,
        endpoints = app.endpoints,
        activeSessionStore = ActiveSessionStore(app),
    )

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { profileRepository.me() } }
            outcome
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            email = snapshot.email,
                            displayName = snapshot.name.orEmpty(),
                            nameDraft = snapshot.name.orEmpty(),
                            consentValues = valuesOf(snapshot.consent),
                            errorMessage = null,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "계정 정보를 불러오지 못했어요. 다시 시도해 주세요.",
                        )
                    }
                }
            refreshPermissions()
        }
    }

    /** Re-read on every resume: the user may have changed a permission in the system settings. */
    fun refreshPermissions() {
        _state.update { it.copy(permissions = readPermissions()) }
    }

    fun onNameDraftChanged(value: String) =
        _state.update { it.copy(nameDraft = value, nameSavedMessage = null, errorMessage = null) }

    fun saveDisplayName() {
        val current = _state.value
        if (!current.canSaveName) return
        val name = current.nameDraft.trim()
        val body = JSONObject()
            .put("name", if (name.isBlank()) JSONObject.NULL else name)
            .toString()

        _state.update { it.copy(isSavingName = true, nameSavedMessage = null, errorMessage = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { app.apiClient.execute(ApiRequest("PATCH", app.endpoints.me, body = body)) }
            }
            outcome
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        _state.update {
                            it.copy(
                                isSavingName = false,
                                displayName = name,
                                nameDraft = name,
                                nameSavedMessage = "표시 이름을 저장했어요.",
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(isSavingName = false, errorMessage = messageFor(response.statusCode))
                        }
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isSavingName = false, errorMessage = messageFor(error)) }
                }
        }
    }

    fun onConsentToggled(key: ConsentKey, value: Boolean) {
        if (key.required) return
        _state.update {
            it.copy(
                consentValues = it.consentValues + (key to value),
                consentSavedMessage = null,
                errorMessage = null,
            )
        }
    }

    /** Appends a new snapshot. The previous snapshot stays exactly where it is. */
    fun saveConsent() {
        val current = _state.value
        if (current.isSavingConsent) return
        val selection = runCatching { current.toSelection() }.getOrElse {
            _state.update { it.copy(errorMessage = "필수 동의는 앱에서 해제할 수 없어요.") }
            return
        }

        _state.update { it.copy(isSavingConsent = true, consentSavedMessage = null, errorMessage = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { app.apiClient.appendConsent(selection) }
            }
            outcome
                .onSuccess {
                    // Biosignal consent gone means the upload loop must not survive the save.
                    if (!selection.biosignal) MonitoringService.stop(app)
                    _state.update {
                        it.copy(
                            isSavingConsent = false,
                            consentSavedMessage = "동의 내용을 새 기록으로 저장했어요.",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isSavingConsent = false, errorMessage = messageFor(error)) }
                }
        }
    }

    fun onCurrentPasswordChanged(value: String) =
        _state.update {
            it.copy(
                currentPassword = value,
                currentPasswordError = null,
                passwordChangedMessage = null,
                errorMessage = null,
            )
        }

    fun onNewPasswordChanged(value: String) =
        _state.update { it.copy(newPassword = value, passwordChangedMessage = null, errorMessage = null) }

    fun onConfirmPasswordChanged(value: String) =
        _state.update { it.copy(confirmPassword = value, passwordChangedMessage = null, errorMessage = null) }

    /**
     * The 12-character minimum is the server contract, checked here to avoid a round trip.
     *
     * The call carries `treatUnauthorizedAsResponse = true`: the shared client still refreshes and
     * replays, so a genuinely expired token is recovered, but the endpoint's own `401` comes back as
     * a response instead of clearing the session. That is what separates a wrong current password
     * — a field-level error the user retries in place — from a session that is really gone. 401
     * handling itself stays in [AuthenticatedApiClient]; the mapping here is
     * [PasswordChangeOutcomePolicy] and nothing else.
     *
     * On success the server revokes every session, so the app signs out rather than sitting on a
     * session the server has already killed.
     */
    fun changePassword() {
        val current = _state.value
        if (!current.canChangePassword) {
            _state.update {
                it.copy(
                    errorMessage = when {
                        !it.newPasswordLongEnough ->
                            "새 비밀번호는 ${PatientSignupRequest.MIN_PASSWORD_LENGTH}자 이상이어야 해요."
                        !it.newPasswordsMatch -> "새 비밀번호가 서로 달라요."
                        else -> "현재 비밀번호를 입력해 주세요."
                    },
                )
            }
            return
        }

        val body = JSONObject()
            .put("currentPassword", current.currentPassword)
            .put("newPassword", current.newPassword)
            .toString()

        _state.update {
            it.copy(
                isChangingPassword = true,
                passwordChangedMessage = null,
                currentPasswordError = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.apiClient.execute(
                        ApiRequest(
                            "POST",
                            app.endpoints.changePassword,
                            body = body,
                            treatUnauthorizedAsResponse = true,
                        ),
                    )
                }
            }
            val outcome = result.fold(
                onSuccess = { PasswordChangeOutcomePolicy.of(it.statusCode) },
                onFailure = { PasswordChangeOutcomePolicy.of(it) },
            )
            applyPasswordChangeOutcome(outcome, result.getOrNull()?.statusCode, result.exceptionOrNull())
        }
    }

    private suspend fun applyPasswordChangeOutcome(
        outcome: PasswordChangeOutcome,
        statusCode: Int?,
        error: Throwable?,
    ) {
        val rejected = PasswordChangeOutcomePolicy.isCurrentPasswordRejected(outcome)
        _state.update {
            it.copy(
                isChangingPassword = false,
                // The new password leaves memory either way; the current one is kept only while the
                // user is still on the screen correcting it.
                currentPassword = if (rejected) it.currentPassword else "",
                newPassword = "",
                confirmPassword = "",
                currentPasswordError = if (rejected) "현재 비밀번호가 올바르지 않아요." else null,
                passwordChangedMessage = when (outcome) {
                    PasswordChangeOutcome.CHANGED ->
                        "비밀번호를 변경했어요. 보안을 위해 다시 로그인해 주세요."
                    else -> null
                },
                errorMessage = when (outcome) {
                    PasswordChangeOutcome.CHANGED, PasswordChangeOutcome.WRONG_CURRENT_PASSWORD -> null
                    PasswordChangeOutcome.SESSION_EXPIRED -> "로그인이 만료됐어요. 다시 로그인해 주세요."
                    PasswordChangeOutcome.FAILED ->
                        error?.let(::messageFor) ?: messageFor(statusCode ?: 0)
                },
            )
        }
        if (PasswordChangeOutcomePolicy.signsOut(outcome)) {
            // Password change already revoked the server sessions; expired sessions are already
            // unusable. Both paths must still complete their local teardown.
            tearDownAndSignOut(requireRemoteLogout = false)
        }
    }

    fun logout() {
        if (_state.value.isSigningOut) return
        _state.update { it.copy(isSigningOut = true, errorMessage = null) }
        viewModelScope.launch { tearDownAndSignOut(requireRemoteLogout = true) }
    }

    /**
     * The full teardown of PRD §NT-09: the server refresh token, the monitoring service (which owns
     * the SSE connection, the Watch upload loop and the prediction relay), the active session id,
     * the pending chat body and every per-user cache. Nothing is left running afterwards.
     */
    private suspend fun tearDownAndSignOut(requireRemoteLogout: Boolean) {
        if (requireRemoteLogout) {
            val failure = withContext(Dispatchers.IO) {
                runCatching { app.apiClient.logout() }.exceptionOrNull()
            }
            if (failure != null) {
                _state.update {
                    it.copy(isSigningOut = false, errorMessage = messageFor(failure))
                }
                return
            }
        } else {
            runCatching { app.apiClient.clearSession() }
        }
        // Stopped from the main thread while the app is still in the foreground.
        runCatching { MonitoringService.stop(app) }
        withContext(Dispatchers.IO) {
            runCatching { sessionRepository.clearActiveSession() }
            runCatching { app.pendingChatStore.clear() }
            runCatching { clearPerUserCaches() }
        }
        _state.update { it.copy(isSigningOut = false, signedOut = true) }
    }

    /**
     * Every per-user store on disk, so signing in as a different user shows none of the previous
     * user's state. The acknowledged product-notice version is device-local rather than per-user and
     * is deliberately kept.
     *
     * `DashboardRepository` fetches NT-08 fresh on every open and persists nothing — the PPG preview
     * is explicitly `no-store` — so there is no dashboard cache API to call here. This sweep covers
     * whatever any screen has left in preferences, including a dashboard cache added later.
     */
    private fun clearPerUserCaches() {
        val directory = File(app.applicationInfo.dataDir, "shared_prefs")
        directory.listFiles()?.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            if (!name.startsWith(PREFERENCE_PREFIX) || name in DEVICE_LOCAL_PREFERENCES) return@forEach
            runCatching {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    private fun readPermissions(): Map<DevicePermission, Boolean> = mapOf(
        DevicePermission.NOTIFICATION to notificationsAllowed(),
        DevicePermission.MICROPHONE to granted(Manifest.permission.RECORD_AUDIO),
        DevicePermission.CAMERA to granted(Manifest.permission.CAMERA),
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** Below API 33 there is no runtime permission, so the channel state is the honest answer. */
    private fun notificationsAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(app).areNotificationsEnabled()
        }

    private fun valuesOf(consent: ConsentSelection?): Map<ConsentKey, Boolean> =
        ConsentKey.entries.associateWith { key ->
            when (key) {
                ConsentKey.TOS -> consent?.tos ?: true
                ConsentKey.PRIVACY -> consent?.privacy ?: true
                ConsentKey.SENSITIVE -> consent?.sensitive ?: true
                ConsentKey.BIOSIGNAL -> consent?.biosignal == true
                ConsentKey.AI_ANALYSIS -> consent?.aiAnalysis == true
                ConsentKey.NOTIFICATION -> consent?.notification == true
                ConsentKey.VOICE -> consent?.voice == true
                ConsentKey.CAMERA_RPPG -> consent?.cameraRppg == true
                ConsentKey.FACE_VIDEO_RETENTION -> consent?.faceVideoRetention == true
                ConsentKey.REPORT_GENERATION -> consent?.reportGeneration == true
            }
        }

    private fun SettingsUiState.toSelection(): ConsentSelection = ConsentSelection(
        tos = consentValues[ConsentKey.TOS] != false,
        privacy = consentValues[ConsentKey.PRIVACY] != false,
        sensitive = consentValues[ConsentKey.SENSITIVE] != false,
        biosignal = consentValues[ConsentKey.BIOSIGNAL] == true,
        aiAnalysis = consentValues[ConsentKey.AI_ANALYSIS] == true,
        notification = consentValues[ConsentKey.NOTIFICATION] == true,
        reportGeneration = consentValues[ConsentKey.REPORT_GENERATION] == true,
        voice = consentValues[ConsentKey.VOICE] == true,
        cameraRppg = consentValues[ConsentKey.CAMERA_RPPG] == true,
        faceVideoRetention = consentValues[ConsentKey.FACE_VIDEO_RETENTION] == true,
    )

    /** Never surfaces a server address, a status code or a stack trace. */
    private fun messageFor(error: Throwable): String = when {
        error is AuthenticationRequiredException -> "로그인이 필요해요. 다시 로그인해 주세요."
        error is ApiHttpException -> messageFor(error.statusCode)
        else -> "네트워크 상태를 확인하고 다시 시도해 주세요."
    }

    private fun messageFor(statusCode: Int): String = when (statusCode) {
        400, 422 -> "입력 내용을 다시 확인해 주세요."
        403 -> "이 작업을 지금 사용할 수 없어요."
        in 500..599 -> "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
        else -> "처리하지 못했어요. 잠시 후 다시 시도해 주세요."
    }

    companion object {
        private const val PREFERENCE_PREFIX = "neurotruth_"
        private val DEVICE_LOCAL_PREFERENCES = setOf("neurotruth_notice")

        fun factory(app: NeuroTruthApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(app) as T
            }
    }
}
