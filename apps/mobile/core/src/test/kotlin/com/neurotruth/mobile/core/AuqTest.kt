package com.neurotruth.mobile.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuqTest {

    private fun answers(vararg values: Int): Map<Int, Int> =
        values.mapIndexed { index, value -> (index + 1) to value }.toMap()

    @Test
    fun `the instrument is eight items on a zero to six scale`() {
        assertEquals(8, Auq.ITEM_COUNT)
        assertEquals(8, Auq.QUESTIONS.size)
        assertEquals(7, Auq.RESPONSE_LABELS.size)
        assertEquals(0, Auq.SCALE_MIN)
        assertEquals(48, Auq.SCALE_MAX)
    }

    @Test
    fun `labels map to zero-based api values`() {
        assertEquals("매우 그렇지 않다", Auq.labelOf(0))
        assertEquals("보통이다", Auq.labelOf(3))
        assertEquals("매우 그렇다", Auq.labelOf(6))
    }

    @Test
    fun `all zero responses are a valid total of zero, not an empty form`() {
        val result = Auq.buildResult(answers(0, 0, 0, 0, 0, 0, 0, 0), capturedAtMs = 1L)
        assertNotNull(result)
        assertEquals(0, result!!.rawTotalScore)
        assertEquals(0, result.totalScore)
    }

    @Test
    fun `a partial or out of range form cannot be built`() {
        assertNull(Auq.buildResult(answers(3, 3, 3, 3, 3, 3, 3), capturedAtMs = 1L))
        assertNull(Auq.buildResult(answers(3, 3, 3, 3, 3, 3, 3, 7), capturedAtMs = 1L))
        assertNull(Auq.buildResult(answers(3, 3, 3, 3, 3, 3, 3, -1), capturedAtMs = 1L))
    }

    @Test
    fun `the submission body carries the required phase and attemptNo`() {
        val result = Auq.buildResult(answers(3, 4, 2, 3, 5, 1, 3, 4), capturedAtMs = 1_784_160_000_000L)!!
        val body = JSONObject(AuqPayload.toJson(result, attemptNo = 1))

        // phase and attemptNo have no server-side defaults; omitting them is a schema 422.
        assertEquals("pre_intervention", body.getString("phase"))
        assertEquals(1, body.getInt("attemptNo"))

        assertEquals("AUQ", body.getString("instrumentCode"))
        assertEquals("2.0", body.getString("version"))
        assertEquals(25, body.getInt("rawScore"))
        assertEquals(0, body.getInt("scaleMin"))
        assertEquals(48, body.getInt("scaleMax"))

        val nested = body.getJSONObject("answers")
        assertEquals(8, nested.getJSONArray("responses").length())
        assertEquals(8, nested.getJSONArray("scoredItems").length())
        assertEquals(25, nested.getInt("rawTotalScore"))
        assertEquals(1_784_160_000_000L, nested.getLong("capturedAtMs"))
    }

    @Test
    fun `responses and scored items match while no item is reverse scored`() {
        val result = Auq.buildResult(answers(3, 4, 2, 3, 5, 1, 3, 4), capturedAtMs = 1L)!!
        assertEquals(result.responses, result.scoredItems)
        assertEquals(result.rawTotalScore, result.totalScore)
    }

    @Test
    fun `reverse scoring inverts on the same scale when configured`() {
        assertEquals(6, Auq.correctedScore(0, reverseScored = true))
        assertEquals(3, Auq.correctedScore(3, reverseScored = true))
        assertEquals(0, Auq.correctedScore(6, reverseScored = true))
        assertEquals(2, Auq.correctedScore(2, reverseScored = false))
    }

    @Test
    fun `skipping posts nothing`() {
        assertTrue(AuqSubmissionPolicy.shouldPostAssessment(AuqChoice.COMPLETE))
        assertFalse(AuqSubmissionPolicy.shouldPostAssessment(AuqChoice.SKIP))
    }

    @Test
    fun `an ambiguous submission is never retried automatically`() {
        assertFalse(AuqSubmissionPolicy.AUTO_RETRY_ALLOWED)
        assertEquals(1, AuqSubmissionPolicy.nextAttemptNo(null))
        assertEquals(2, AuqSubmissionPolicy.nextAttemptNo(1))
    }

    @Test
    fun `an attempt number below one is rejected`() {
        val result = Auq.buildResult(answers(1, 1, 1, 1, 1, 1, 1, 1), capturedAtMs = 1L)!!
        assertThrows(IllegalArgumentException::class.java) {
            AuqPayload.toJson(result, attemptNo = 0)
        }
    }
}
