package com.neurotruth.mobile.service

import com.neurotruth.mobile.core.AlertClaimStore
import com.neurotruth.mobile.core.CravingPrediction

/**
 * Deduplicates what arrives on both transports.
 *
 * The `POST /api/sensor-windows` response and the SSE stream can deliver the same prediction and the
 * same alert. Both go through this ledger so a prediction is applied once and the same `alertId`
 * never produces two notifications.
 *
 * Both claims are [AlertClaimStore] instances from `:core` — the retention bound and the
 * first-claim-wins semantics are already unit-tested there.
 */
class PredictionLedger(
    private val predictionClaims: AlertClaimStore = AlertClaimStore(),
    private val alertClaims: AlertClaimStore = AlertClaimStore(),
) {

    /** True the first time this prediction is seen. An id-less prediction is always let through. */
    fun claimPrediction(prediction: CravingPrediction): Boolean {
        val id = prediction.predictionId ?: return true
        return predictionClaims.claim(id)
    }

    /** True the first time this alert is seen. An alert with no id can never be presented. */
    fun claimAlert(alertId: String?): Boolean {
        if (alertId.isNullOrBlank()) return false
        return alertClaims.claim(alertId)
    }

    fun clear() {
        predictionClaims.clear()
        alertClaims.clear()
    }
}
