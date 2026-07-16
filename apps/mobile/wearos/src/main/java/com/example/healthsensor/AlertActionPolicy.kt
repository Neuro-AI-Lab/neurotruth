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
        BinaryPredictionContract.requireClass(cravingClass)
        parseAction(alertAction)?.let { return it }
        if (hasAlertMetadata) {
            return parseLevel(alertLevel) ?: AlertAction.NONE
        }

        // Binary class is display-only on the Watch. User-facing actions must
        // come from backend alert metadata, never from class 1 by itself.
        return AlertAction.NONE
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

object BinaryPredictionContract {
    fun requireClass(value: Int): Int {
        require(value in 0..1) { "binary prediction class must be 0 or 1" }
        return value
    }
}
