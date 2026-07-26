package com.neurotruth.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CravingStageTest {

    @Test
    fun `band boundaries are inclusive at the lower edge`() {
        assertEquals(CravingStage.SAFE, CravingStage.of(0.0f))
        assertEquals(CravingStage.SAFE, CravingStage.of(0.2499f))
        assertEquals(CravingStage.OBSERVE, CravingStage.of(0.25f))
        assertEquals(CravingStage.OBSERVE, CravingStage.of(0.4999f))
        assertEquals(CravingStage.CAUTION, CravingStage.of(0.50f))
        assertEquals(CravingStage.CAUTION, CravingStage.of(0.7499f))
        assertEquals(CravingStage.SEVERE, CravingStage.of(0.75f))
        assertEquals(CravingStage.SEVERE, CravingStage.of(1.0f))
    }

    @Test
    fun `a probability the binary classifier calls high is still 주의`() {
        // classCode is already "high" at p >= 0.5. Deriving the stage from it would show 위험 here.
        val prediction = CravingPrediction(
            cravingProbability = 0.60f,
            timestampMs = 1_784_160_000_000L,
            source = SOURCE_WATCH,
        )
        assertEquals(CravingStage.CAUTION, prediction.stage)
        assertEquals("주의", prediction.stage.label)
        assertEquals("주의가 필요해요, 술이 드시고 싶으신가요?", prediction.stage.message)
    }

    @Test
    fun `patient copy is fixed per band`() {
        assertEquals("안정", CravingStage.SAFE.label)
        assertEquals("아무 문제 없어요!", CravingStage.SAFE.message)
        assertEquals("관찰", CravingStage.OBSERVE.label)
        assertEquals("관찰이 필요해요, 심각하진 않아요!", CravingStage.OBSERVE.message)
        assertEquals("주의", CravingStage.CAUTION.label)
        assertEquals("위험", CravingStage.SEVERE.label)
        assertEquals("갈망이 높게 감지됐어요. 챗봇과 대화를 시작할까요?", CravingStage.SEVERE.message)
    }

    @Test
    fun `stageCounts keys map back to bands`() {
        assertEquals(CravingStage.SAFE, CravingStage.fromStageCountsKey("low"))
        assertEquals(CravingStage.OBSERVE, CravingStage.fromStageCountsKey("observe"))
        assertEquals(CravingStage.CAUTION, CravingStage.fromStageCountsKey("caution"))
        assertEquals(CravingStage.SEVERE, CravingStage.fromStageCountsKey("high"))
        assertNull(CravingStage.fromStageCountsKey("severe"))
    }

    @Test
    fun `no measurement is not a band`() {
        assertNull(CravingStage.ofOrNull(null))
        assertEquals("측정 데이터 없음", CravingStage.NO_DATA_LABEL)
    }

    @Test
    fun `probabilities outside 0 to 1 are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CravingStage.of(-0.01f) }
        assertThrows(IllegalArgumentException::class.java) { CravingStage.of(1.01f) }
        assertThrows(IllegalArgumentException::class.java) { CravingStage.of(Float.NaN) }
    }
}
