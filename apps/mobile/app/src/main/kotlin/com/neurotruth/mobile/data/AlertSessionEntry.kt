package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.SessionEntryPoint

/**
 * Carries a tap on the notification's `지금 대화하기` from [com.neurotruth.mobile.MainActivity] into
 * the chat session, so the session is created as `alert_checkin` with its `triggerAlertId` rather
 * than as a plain manual check-in.
 *
 * The pending alert is consumed exactly once. Without that, a later visit to the 챗봇 tab would
 * still be attributed to the alert and mislabel the session in the research dataset.
 */
object AlertSessionEntry {

    @Volatile
    private var pendingEntry: Pair<SessionEntryPoint, String?>? = null

    @Synchronized
    fun arm(alertId: String) {
        if (alertId.isNotBlank()) pendingEntry = SessionEntryPoint.ALERT to alertId
    }

    /** Carries a non-alert entry such as a completed rPPG measurement into ChatViewModel. */
    @Synchronized
    fun arm(entryPoint: SessionEntryPoint) {
        require(entryPoint != SessionEntryPoint.ALERT) { "alert entry requires an alert id" }
        pendingEntry = entryPoint to null
    }

    @Synchronized
    fun consume(): Pair<SessionEntryPoint, String?> {
        val entry = pendingEntry
        pendingEntry = null
        return entry ?: (SessionEntryPoint.CHAT_TAB to null)
    }

    @Synchronized
    fun clear() {
        pendingEntry = null
    }
}
