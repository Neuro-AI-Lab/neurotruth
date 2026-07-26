package com.neurotruth.mobile.core

/**
 * The three places that open a conversation. All of them go through one `ensureSession()`, so the
 * entry point — not the call site — carries the `sessionType`.
 *
 * `sessionType` is a closed server enum; any other string is a 422. Hardcoding `manual_checkin`
 * everywhere would mislabel every alert-driven session in the research dataset.
 */
enum class SessionEntryPoint(val sessionType: String) {
    /** NT-05 `지금 대화하기`. */
    ALERT("alert_checkin"),

    /** NT-04 챗봇 tab. */
    CHAT_TAB("manual_checkin"),

    /** NT-04R rPPG completion. User-initiated, so it is a manual check-in. */
    RPPG_COMPLETION("manual_checkin"),
    ;

    val carriesTriggerAlertId: Boolean
        get() = this == ALERT
}

data class SessionStartRequest(
    val sessionType: String,
    val triggerAlertId: String?,
) {
    companion object {
        fun of(entryPoint: SessionEntryPoint, alertId: String? = null): SessionStartRequest {
            require(entryPoint.carriesTriggerAlertId || alertId == null) {
                "triggerAlertId is only carried by ${SessionEntryPoint.ALERT}"
            }
            return SessionStartRequest(
                sessionType = entryPoint.sessionType,
                triggerAlertId = if (entryPoint.carriesTriggerAlertId) alertId else null,
            )
        }
    }
}

/**
 * Distinguishes a newly created session from a resumed one.
 *
 * `POST /api/sessions` returns no `created` flag, and `sessionId`, `status`, `interactionPhase` and
 * the timestamps look identical in both cases. The server attaches its neutral opening invitation
 * only when it creates the session, so a non-blank `assistantText` is the discriminator.
 *
 * Comparing `sessionId` against a locally cached value is not equivalent: it misreports after a
 * reinstall, after the local store is cleared, and after the server finalizes a session on
 * inactivity timeout — each of which would re-show the AUQ for a conversation already in progress.
 */
object SessionCreationPolicy {
    fun wasCreated(assistantText: String?): Boolean = !assistantText.isNullOrBlank()

    /**
     * A newly created session offers the AUQ first; a resumed one goes straight back to dialogue.
     *
     * [auqScreenAvailable] remains explicit so stripped-down test clients can omit the AUQ screen
     * without changing how new and resumed sessions are distinguished.
     */
    fun requiresAuq(assistantText: String?, auqScreenAvailable: Boolean): Boolean =
        auqScreenAvailable && wasCreated(assistantText)
}
