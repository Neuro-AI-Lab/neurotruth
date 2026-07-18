package com.example.healthsensor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.MessageDigest

data class MobileAuthState(
    val user: AuthUser? = null,
    val consent: ConsentSelection? = null
) {
    val authenticated: Boolean get() = user != null
    val canUploadBiosignal: Boolean get() = authenticated && consent?.biosignal == true
    val canReceiveAiPrediction: Boolean get() = canUploadBiosignal && consent?.aiAnalysis == true
    val canCaptureRppg: Boolean get() = canReceiveAiPrediction &&
        consent?.cameraRppg == true && consent.faceVideoRetention
    val canUseVoice: Boolean get() = authenticated && consent?.voice == true
    val canGenerateReport: Boolean get() = authenticated && consent?.reportGeneration == true
    val canNotify: Boolean get() = authenticated && consent?.notification == true
}

/** Process-wide non-secret auth/consent state. Backend credentials remain inside AuthenticatedApiClient. */
object MobileAuthRuntime {
    private val _state = MutableStateFlow(MobileAuthState())
    val state: StateFlow<MobileAuthState> = _state

    fun accept(context: Context, user: AuthUser, consent: ConsentSelection? = null) {
        val selected = consent ?: loadConsent(context, user.id)
        if (consent != null) saveConsent(context, user.id, consent)
        _state.value = MobileAuthState(user, selected)
    }

    fun signOut() {
        _state.value = MobileAuthState()
    }

    fun canUpload(): Boolean = _state.value.canUploadBiosignal
    fun canReceivePredictions(): Boolean = _state.value.canReceiveAiPrediction

    private fun saveConsent(context: Context, userId: String, consent: ConsentSelection) {
        preferences(context).edit()
            .putString(keyFor(userId), consent.toJson().toString())
            .apply()
    }

    private fun loadConsent(context: Context, userId: String): ConsentSelection? =
        preferences(context).getString(keyFor(userId), null)?.let { encoded ->
            runCatching { ConsentSelection.fromJson(JSONObject(encoded)) }.getOrNull()
        }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("patient_consent", Context.MODE_PRIVATE)

    private fun keyFor(userId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "consent_$digest"
    }
}

object LogoutCleanupPolicy {
    fun execute(stopMonitoring: () -> Unit, clearRuntime: () -> Unit) {
        stopMonitoring()
        clearRuntime()
    }
}

object CredentialRevocationCleanupPolicy {
    fun execute(
        stopMonitoring: () -> Unit,
        clearConversationSessions: () -> Unit,
        clearCredentials: () -> Unit,
        clearRuntime: () -> Unit
    ) {
        stopMonitoring()
        clearConversationSessions()
        clearCredentials()
        clearRuntime()
    }
}

object NotificationPresentationPolicy {
    fun shouldPresent(canNotify: Boolean, alertClaimed: Boolean): Boolean =
        canNotify && alertClaimed

    fun suppressWatchPresentation(canNotify: Boolean, interventionActive: Boolean): Boolean =
        !canNotify || interventionActive
}
