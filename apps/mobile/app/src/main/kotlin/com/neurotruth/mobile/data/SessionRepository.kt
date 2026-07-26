package com.neurotruth.mobile.data

import android.content.Context
import com.neurotruth.mobile.core.SessionCreationPolicy
import com.neurotruth.mobile.core.SessionEntryPoint
import com.neurotruth.mobile.core.SessionStartRequest
import com.neurotruth.mobile.core.net.ApiEndpoints
import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.ApiRequest
import com.neurotruth.mobile.core.net.AuthenticatedApiClient
import org.json.JSONObject

/**
 * The active `sessionId`, kept so a restart resumes the same conversation.
 *
 * PRD §7 places this in ordinary preferences: it is an opaque identifier, not conversation content.
 * It is only ever a fast path — [SessionRepository.ensureSession] still asks the server, because the
 * server may have finalized the session on its inactivity timeout while the app was away.
 */
class ActiveSessionStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("neurotruth_session", Context.MODE_PRIVATE)

    fun load(): String? = preferences.getString(KEY, null)

    fun save(sessionId: String) {
        preferences.edit().putString(KEY, sessionId).commit()
    }

    fun clear() {
        preferences.edit().remove(KEY).commit()
    }

    private companion object {
        const val KEY = "active_session_id"
    }
}

/** What `ensureSession()` resolved, already reduced to a routing decision. */
data class SessionEntry(
    val sessionId: String,
    val assistantText: String?,
    val wasCreated: Boolean,
    val requiresAuq: Boolean,
    val inactivityTimeoutSeconds: Int,
)

/**
 * The single session entry seam of PRD §5.3.
 *
 * NT-05 `지금 대화하기`, the NT-04 챗봇 tab and NT-04R completion all land here, so the caller passes
 * its own [SessionEntryPoint] and the `sessionType` is derived from it — hardcoding
 * `manual_checkin` would mislabel every alert-driven session in the research dataset.
 *
 * Newness is decided by `assistantText`, never by comparing `sessionId` against a cached value: that
 * comparison misreports after a reinstall, after the local store is cleared, and after the server
 * finalizes a session on timeout, each of which would re-show the AUQ mid-conversation.
 */
class SessionRepository(
    private val client: AuthenticatedApiClient,
    private val endpoints: ApiEndpoints,
    private val activeSessionStore: ActiveSessionStore,
) {

    fun activeSessionId(): String? = activeSessionStore.load()

    fun ensureSession(
        entryPoint: SessionEntryPoint,
        alertId: String? = null,
    ): SessionEntry {
        val request = SessionStartRequest.of(entryPoint, alertId)
        val body = JSONObject()
            .put("sessionType", request.sessionType)
            .put("triggerAlertId", request.triggerAlertId ?: JSONObject.NULL)
            .toString()

        val response = client.execute(ApiRequest("POST", endpoints.sessions, body = body))
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)

        val json = JSONObject(response.body)
        val sessionId = json.optString("sessionId").takeIf { it.isNotBlank() }
            ?: throw ApiHttpException(response.statusCode, "missing sessionId")
        val assistantText =
            if (json.isNull("assistantText")) null else json.optString("assistantText").ifBlank { null }

        activeSessionStore.save(sessionId)

        return SessionEntry(
            sessionId = sessionId,
            assistantText = assistantText,
            wasCreated = SessionCreationPolicy.wasCreated(assistantText),
            // A new session visits AUQ first; a resumed session returns directly to dialogue.
            requiresAuq = SessionCreationPolicy.requiresAuq(assistantText, AUQ_SCREEN_AVAILABLE),
            inactivityTimeoutSeconds = json.optInt(
                "inactivityTimeoutSeconds",
                DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
            ),
        )
    }

    fun finish(sessionId: String) {
        val response = client.execute(ApiRequest("POST", endpoints.finish(sessionId), body = "{}"))
        // Clear the local resume state only after the server confirms the finish. Clearing first
        // would drop the id on a network failure while the server still holds the session.
        if (!response.isSuccessful) throw ApiHttpException(response.statusCode, response.body)
        activeSessionStore.clear()
    }

    fun clearActiveSession() = activeSessionStore.clear()

    companion object {
        /** NT-06 is part of the shipped Phone flow. */
        const val AUQ_SCREEN_AVAILABLE: Boolean = true
        const val DEFAULT_INACTIVITY_TIMEOUT_SECONDS: Int = 3_600
    }
}
