package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateCheckScoringTest {

    private val userFacingQuestions = listOf(
        StateCheckQuestion(1, "q1"),
        StateCheckQuestion(2, "q2"),
        StateCheckQuestion(3, "q3"),
        StateCheckQuestion(4, "q4"),
        StateCheckQuestion(5, "q5"),
        StateCheckQuestion(6, "q6"),
        StateCheckQuestion(7, "q7"),
        StateCheckQuestion(8, "q8")
    )

    private val reverseScoredQuestions = listOf(
        StateCheckQuestion(1, "q1"),
        StateCheckQuestion(2, "q2", reverseScored = true),
        StateCheckQuestion(3, "q3"),
        StateCheckQuestion(4, "q4"),
        StateCheckQuestion(5, "q5"),
        StateCheckQuestion(6, "q6"),
        StateCheckQuestion(7, "q7", reverseScored = true),
        StateCheckQuestion(8, "q8")
    )

    @Test
    fun userFacingQuestionsScoreZeroToSixInTheSameDirection() {
        val allLow = StateCheckScoring.buildResult(
            questions = userFacingQuestions,
            responsesByQuestion = userFacingQuestions.associate { it.number to 0 },
            timestampMs = 123L,
            triggerClass = 2
        )!!
        val allHigh = StateCheckScoring.buildResult(
            questions = userFacingQuestions,
            responsesByQuestion = userFacingQuestions.associate { it.number to 6 },
            timestampMs = 124L,
            triggerClass = 2
        )!!

        assertEquals(0f, allLow.meanScore, 0.0001f)
        assertEquals(6f, allHigh.meanScore, 0.0001f)
        assertEquals(allLow.rawMeanScore, allLow.meanScore, 0.0001f)
        assertEquals(allHigh.rawMeanScore, allHigh.meanScore, 0.0001f)
    }

    @Test
    fun supportsReverseScoredItemsWhenConfigured() {
        val result = StateCheckScoring.buildResult(
            questions = reverseScoredQuestions,
            responsesByQuestion = reverseScoredQuestions.associate { it.number to 6 },
            timestampMs = 123L,
            triggerClass = 2
        )!!

        assertEquals(listOf(6, 0, 6, 6, 6, 6, 0, 6), result.scoredItems)
        assertEquals(48, result.rawTotalScore)
        assertEquals(6f, result.rawMeanScore, 0.0001f)
        assertEquals(36, result.totalScore)
        assertEquals(4.5f, result.meanScore, 0.0001f)
    }

    @Test
    fun neutralResponsesStayNeutralAfterReverseScoring() {
        val result = StateCheckScoring.buildResult(
            questions = reverseScoredQuestions,
            responsesByQuestion = reverseScoredQuestions.associate { it.number to 3 },
            timestampMs = 123L,
            triggerClass = 2
        )!!

        assertEquals(List(8) { 3 }, result.scoredItems)
        assertEquals(3f, result.rawMeanScore, 0.0001f)
        assertEquals(3f, result.meanScore, 0.0001f)
    }

    @Test
    fun rejectsMissingOrOutOfRangeResponses() {
        assertNull(
            StateCheckScoring.buildResult(
                questions = userFacingQuestions,
                responsesByQuestion = mapOf(1 to 4),
                timestampMs = 123L,
                triggerClass = 2
            )
        )
        assertNull(
            StateCheckScoring.buildResult(
                questions = userFacingQuestions,
                responsesByQuestion = userFacingQuestions.associate { it.number to 7 },
                timestampMs = 123L,
                triggerClass = 2
            )
        )
    }
}
