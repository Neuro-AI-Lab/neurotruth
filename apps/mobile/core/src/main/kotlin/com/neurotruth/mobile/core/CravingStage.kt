package com.neurotruth.mobile.core

/**
 * Patient-facing craving band.
 *
 * The stage is derived from `cravingProbability` alone. A prediction payload also carries `class`,
 * `classCode` and `classProbabilities`, but those come from a *binary* classifier whose `classCode`
 * is already `"high"` at p >= 0.5. Deriving the stage from them would render 위험 across the whole
 * 주의 band.
 *
 * [stageCountsKey] matches the `stageCounts` keys of `GET /api/me/craving-dashboard`. Those keys
 * appear only in the dashboard aggregate, never in a prediction payload.
 *
 * These bands are a research display convention, not a clinical risk level or diagnostic cutoff.
 */
enum class CravingStage(
    val stageCountsKey: String,
    val label: String,
    val message: String,
) {
    SAFE("low", "안정", "아무 문제 없어요!"),
    OBSERVE("observe", "관찰", "관찰이 필요해요, 심각하진 않아요!"),
    CAUTION("caution", "주의", "주의가 필요해요, 술이 드시고 싶으신가요?"),
    SEVERE("high", "위험", "갈망이 높게 감지됐어요. 챗봇과 대화를 시작할까요?"),
    ;

    companion object {
        /** Shown when there is no measurement. Never substitute 0% or "낮음". */
        const val NO_DATA_LABEL: String = "측정 데이터 없음"

        const val OBSERVE_THRESHOLD: Float = 0.25f
        const val CAUTION_THRESHOLD: Float = 0.50f
        const val SEVERE_THRESHOLD: Float = 0.75f

        fun of(cravingProbability: Float): CravingStage {
            require(!cravingProbability.isNaN()) { "cravingProbability must not be NaN" }
            require(cravingProbability in 0f..1f) {
                "cravingProbability must be within 0..1 but was $cravingProbability"
            }
            return when {
                cravingProbability < OBSERVE_THRESHOLD -> SAFE
                cravingProbability < CAUTION_THRESHOLD -> OBSERVE
                cravingProbability < SEVERE_THRESHOLD -> CAUTION
                else -> SEVERE
            }
        }

        fun ofOrNull(cravingProbability: Float?): CravingStage? =
            cravingProbability?.let(::of)

        fun fromStageCountsKey(key: String): CravingStage? =
            entries.firstOrNull { it.stageCountsKey == key }
    }
}
