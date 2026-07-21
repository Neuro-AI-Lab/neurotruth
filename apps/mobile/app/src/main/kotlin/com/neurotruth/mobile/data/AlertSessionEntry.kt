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
    private var pendingAlertId: String? = null

    @Synchronized
    fun arm(alertId: String) {
        if (alertId.isNotBlank()) pendingAlertId = alertId
    }

    @Synchronized
    fun consume(): Pair<SessionEntryPoint, String?> {
        val alertId = pendingAlertId
        pendingAlertId = null
        return if (alertId != null) {
            SessionEntryPoint.ALERT to alertId
        } else {
            SessionEntryPoint.CHAT_TAB to null
        }
    }

    @Synchronized
    fun clear() {
        pendingAlertId = null
    }
}
