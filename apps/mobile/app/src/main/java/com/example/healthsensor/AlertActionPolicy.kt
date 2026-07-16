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
    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        alertAction: String?,
        alertLevel: String?,
        hasAlertMetadata: Boolean,
        cravingClass: Int
    ): AlertAction {
        // Binary predictions are overlapping one-second windows. The backend owns the
        // rolling history, downtrend and cooldown rules, so class alone must never alert.
        return parseAction(alertAction) ?: AlertAction.NONE
    }

    fun resolve(prediction: CravingPrediction): AlertAction =
        resolve(
            alertAction = prediction.alert.alertAction,
            alertLevel = prediction.alert.alertLevel,
            hasAlertMetadata = prediction.alert.isPresent,
            cravingClass = prediction.cravingClass
        )

    private fun parseAction(value: String?): AlertAction? =
        when (value.normalized()) {
            "none" -> AlertAction.NONE
            "cooldown" -> AlertAction.COOLDOWN
            "recommend", "recommendation", "recommend_intervention" -> AlertAction.RECOMMEND
            "required", "required_intervention" -> AlertAction.REQUIRED
            else -> null
        }

    private fun String?.normalized(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US)
}
