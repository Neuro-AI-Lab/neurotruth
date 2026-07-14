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
    fun userFacingQuestionsScoreOneToSevenInTheSameDirection() {
        val allLow = StateCheckScoring.buildResult(
            questions = userFacingQuestions,
            responsesByQuestion = userFacingQuestions.associate { it.number to 1 },
            timestampMs = 123L,
            triggerClass = 2
        )!!
        val allHigh = StateCheckScoring.buildResult(
            questions = userFacingQuestions,
            responsesByQuestion = userFacingQuestions.associate { it.number to 7 },
            timestampMs = 124L,
            triggerClass = 2
        )!!

        assertEquals(1f, allLow.meanScore, 0.0001f)
        assertEquals(7f, allHigh.meanScore, 0.0001f)
        assertEquals(allLow.rawMeanScore, allLow.meanScore, 0.0001f)
        assertEquals(allHigh.rawMeanScore, allHigh.meanScore, 0.0001f)
    }

    @Test
    fun supportsReverseScoredItemsWhenConfigured() {
        val result = StateCheckScoring.buildResult(
            questions = reverseScoredQuestions,
            responsesByQuestion = reverseScoredQuestions.associate { it.number to 7 },
            timestampMs = 123L,
            triggerClass = 2
        )!!

        assertEquals(listOf(7, 1, 7, 7, 7, 7, 1, 7), result.scoredItems)
        assertEquals(56, result.rawTotalScore)
        assertEquals(7f, result.rawMeanScore, 0.0001f)
        assertEquals(44, result.totalScore)
        assertEquals(5.5f, result.meanScore, 0.0001f)
    }

    @Test
    fun neutralResponsesStayNeutralAfterReverseScoring() {
        val result = StateCheckScoring.buildResult(
            questions = reverseScoredQuestions,
            responsesByQuestion = reverseScoredQuestions.associate { it.number to 4 },
            timestampMs = 123L,
            triggerClass = 2
        )!!

        assertEquals(List(8) { 4 }, result.scoredItems)
        assertEquals(4f, result.rawMeanScore, 0.0001f)
        assertEquals(4f, result.meanScore, 0.0001f)
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
                responsesByQuestion = userFacingQuestions.associate { it.number to 8 },
                timestampMs = 123L,
                triggerClass = 2
            )
        )
    }
}
