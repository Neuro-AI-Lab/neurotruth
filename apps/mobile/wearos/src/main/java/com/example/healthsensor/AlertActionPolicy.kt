package com.example.healthsensor

import java.util.Locale

enum class AlertAction {
    NONE,
    COOLDOWN,
    RECOMMEND,
    REQUIRED;

    val suppressesUserFacingActions: Boolean
        get() = this == NONE || this == COOLDOWN
}

object AlertActionPolicy {
    fun resolve(
        alertAction: String?,
        alertLevel: String?,
        hasAlertMetadata: Boolean,
        cravingClass: Int
    ): AlertAction {
        parseAction(alertAction)?.let { return it }
        if (hasAlertMetadata) {
            return parseLevel(alertLevel) ?: AlertAction.NONE
        }

        return when (cravingClass) {
            1 -> AlertAction.RECOMMEND
            2 -> AlertAction.REQUIRED
            else -> AlertAction.NONE
        }
    }

    private fun parseAction(value: String?): AlertAction? =
        when (value.normalized()) {
            "none" -> AlertAction.NONE
            "cooldown" -> AlertAction.COOLDOWN
            "recommend", "recommendation", "recommend_intervention" -> AlertAction.RECOMMEND
            "required", "required_intervention" -> AlertAction.REQUIRED
            else -> null
        }

    private fun parseLevel(value: String?): AlertAction? =
        when (value.normalized()) {
            "none" -> AlertAction.NONE
            "recommend", "recommendation" -> AlertAction.RECOMMEND
            "required" -> AlertAction.REQUIRED
            else -> null
        }

    private fun String?.normalized(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US)
}
