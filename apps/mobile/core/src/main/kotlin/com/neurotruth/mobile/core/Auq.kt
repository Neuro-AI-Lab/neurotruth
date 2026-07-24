package com.neurotruth.mobile.core

import org.json.JSONArray
import org.json.JSONObject

data class AuqQuestion(
    val number: Int,
    val text: String,
    val reverseScored: Boolean = false,
)

data class AuqResult(
    val responses: List<Int>,
    val scoredItems: List<Int>,
    val rawTotalScore: Int,
    val totalScore: Int,
    val capturedAtMs: Long,
)

/**
 * The eight-item, seven-choice Korean research adaptation.
 *
 * The screen hides the numbers; the API takes zero-based values. All eight items score in the same
 * direction in this adaptation, so `responses` and `scoredItems` are identical. Scoring rules from
 * another AUQ wording or version must not be applied silently.
 */
object Auq {
    const val INSTRUMENT_CODE: String = "AUQ"
    const val VERSION: String = "2.0"
    const val ITEM_COUNT: Int = 8
    const val MIN_RESPONSE: Int = 0
    const val MAX_RESPONSE: Int = 6
    const val SCALE_MIN: Int = 0
    const val SCALE_MAX: Int = 48
    const val PHASE_PRE_INTERVENTION: String = "pre_intervention"

    /** Index equals the API value. */
    val RESPONSE_LABELS: List<String> = listOf(
        "매우 그렇지 않다",
        "그렇지 않다",
        "조금 그렇지 않다",
        "보통이다",
        "조금 그렇다",
        "그렇다",
        "매우 그렇다",
    )

    val QUESTIONS: List<AuqQuestion> = listOf(
        AuqQuestion(1, "지금 음주 충동이 느껴진다."),
        AuqQuestion(2, "지금 술을 마시지 않고 지나가기 어렵다고 느낀다."),
        AuqQuestion(3, "술 생각이 머릿속에서 쉽게 떠나지 않는다."),
        AuqQuestion(4, "술을 마시면 현재 불편함이 줄어들 것 같다."),
        AuqQuestion(5, "지금 술을 구하거나 마시고 싶은 마음이 강하다."),
        AuqQuestion(6, "술과 관련된 자극에 끌리는 느낌이 있다."),
        AuqQuestion(7, "지금은 음주 욕구를 조절하기 어렵다고 느낀다."),
        AuqQuestion(8, "이 순간 술을 마시고 싶다는 갈망이 있다."),
    )

    fun isValidResponse(value: Int): Boolean = value in MIN_RESPONSE..MAX_RESPONSE

    fun correctedScore(rawScore: Int, reverseScored: Boolean): Int =
        if (reverseScored) MAX_RESPONSE - rawScore else rawScore

    fun labelOf(value: Int): String {
        require(isValidResponse(value)) { "response must be within $MIN_RESPONSE..$MAX_RESPONSE" }
        return RESPONSE_LABELS[value]
    }

    /** Returns null when an item is missing or out of range, so a partial form can never be sent. */
    fun buildResult(responsesByQuestion: Map<Int, Int>, capturedAtMs: Long): AuqResult? {
        val responses = ArrayList<Int>(ITEM_COUNT)
        val scoredItems = ArrayList<Int>(ITEM_COUNT)
        for (question in QUESTIONS) {
            val response = responsesByQuestion[question.number] ?: return null
            if (!isValidResponse(response)) return null
            responses.add(response)
            scoredItems.add(correctedScore(response, question.reverseScored))
        }
        return AuqResult(
            responses = responses,
            scoredItems = scoredItems,
            rawTotalScore = responses.sum(),
            totalScore = scoredItems.sum(),
            capturedAtMs = capturedAtMs,
        )
    }
}

enum class AuqChoice { COMPLETE, SKIP }

object AuqSubmissionPolicy {
    fun shouldPostAssessment(choice: AuqChoice): Boolean = choice == AuqChoice.COMPLETE

    /**
     * This endpoint has no idempotency key, unlike messages, sensor windows and rPPG jobs. A blind
     * retry after a timeout writes two rows for one episode and corrupts the dashboard average, so
     * an ambiguous outcome is treated as failed and only an explicit user resubmit advances the
     * attempt number.
     */
    fun nextAttemptNo(previousAttemptNo: Int?): Int {
        val previous = previousAttemptNo ?: 0
        require(previous >= 0) { "previousAttemptNo must not be negative" }
        return previous + 1
    }

    const val AUTO_RETRY_ALLOWED: Boolean = false
}

object AuqPayload {
    /**
     * `phase` and `attemptNo` are required by the server with no defaults, and the body rejects
     * unknown fields. Omitting either yields a schema 422 rather than the documented
     * `invalid_auq_scale` error, which leaves the user with an uncorrectable failure.
     */
    fun toJson(
        result: AuqResult,
        attemptNo: Int,
        phase: String = Auq.PHASE_PRE_INTERVENTION,
    ): String {
        require(result.responses.size == Auq.ITEM_COUNT) {
            "AUQ requires exactly ${Auq.ITEM_COUNT} responses"
        }
        require(result.scoredItems.size == Auq.ITEM_COUNT) {
            "AUQ requires exactly ${Auq.ITEM_COUNT} scored items"
        }
        require(result.responses.all(Auq::isValidResponse)) {
            "AUQ responses must be within ${Auq.MIN_RESPONSE}..${Auq.MAX_RESPONSE}"
        }
        require(result.totalScore in Auq.SCALE_MIN..Auq.SCALE_MAX) {
            "AUQ total must be within ${Auq.SCALE_MIN}..${Auq.SCALE_MAX}"
        }
        require(attemptNo >= 1) { "attemptNo must be at least 1" }

        val answers = JSONObject()
            .put("responses", JSONArray(result.responses))
            .put("scoredItems", JSONArray(result.scoredItems))
            .put("capturedAtMs", result.capturedAtMs)
            .put("rawTotalScore", result.rawTotalScore)

        return JSONObject()
            .put("instrumentCode", Auq.INSTRUMENT_CODE)
            .put("version", Auq.VERSION)
            .put("phase", phase)
            .put("attemptNo", attemptNo)
            .put("answers", answers)
            .put("rawScore", result.totalScore)
            .put("scaleMin", Auq.SCALE_MIN)
            .put("scaleMax", Auq.SCALE_MAX)
            .toString()
    }
}
