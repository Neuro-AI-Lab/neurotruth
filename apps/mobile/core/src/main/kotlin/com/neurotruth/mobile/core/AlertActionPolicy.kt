package com.neurotruth.mobile.core

enum class AlertAction {
    NONE,
    COOLDOWN,
    RECOMMEND,
    REQUIRED,
    ;

    val suppressesUserFacingActions: Boolean
        get() = this == NONE || this == COOLDOWN
}

/**
 * Resolves whether to present a craving alert.
 *
 * Only the server's `alertAction` decides. Binary predictions are overlapping windows, so the
 * backend owns rolling history, downtrend and cooldown; a class of 1 on its own must never raise an
 * alert. `alertLevel`, the presence of alert metadata and the predicted class are deliberately not
 * consulted — an unrecognized action resolves to [AlertAction.NONE] rather than falling through to
 * them.
 */
object AlertActionPolicy {
    fun resolve(alertAction: String?): AlertAction =
        when (alertAction?.trim()?.lowercase()) {
            "none" -> AlertAction.NONE
            "cooldown" -> AlertAction.COOLDOWN
            "recommend", "recommendation", "recommend_intervention" -> AlertAction.RECOMMEND
            "required", "required_intervention" -> AlertAction.REQUIRED
            else -> AlertAction.NONE
        }

    /** `recommend|required` also drives the separate 대화 권장 marker on Home. */
    fun recommendsConversation(alertAction: String?): Boolean =
        !resolve(alertAction).suppressesUserFacingActions
}

/**
 * Deduplicates alerts that arrive over both the upload response and the SSE stream.
 *
 * The same `alertId` must never produce two notifications.
 */
class AlertClaimStore(private val maxRetained: Int = 200) {
    private val claimed = LinkedHashSet<String>()

    @Synchronized
    fun claim(alertId: String): Boolean {
        if (alertId.isBlank()) return false
        if (!claimed.add(alertId)) return false
        while (claimed.size > maxRetained) {
            val oldest = claimed.first()
            claimed.remove(oldest)
        }
        return true
    }

    @Synchronized
    fun clear() = claimed.clear()
}
