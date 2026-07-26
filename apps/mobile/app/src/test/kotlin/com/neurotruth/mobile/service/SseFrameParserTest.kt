package com.neurotruth.mobile.service

import com.neurotruth.mobile.core.AlertActionPolicy
import com.neurotruth.mobile.core.CravingStage
import com.neurotruth.mobile.core.SOURCE_CAMERA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame parsing for `GET /api/predictions/stream`, plus the id-based deduplication it feeds. */
class SseFrameParserTest {

    @Test
    fun `extracts the payload of a data line`() {
        assertEquals("""{"a":1}""", SseFrameParser.payloadOf("""data: {"a":1}"""))
        assertEquals("""{"a":1}""", SseFrameParser.payloadOf("""data:{"a":1}"""))
    }

    @Test
    fun `skips comment heartbeats`() {
        assertNull(SseFrameParser.payloadOf(": keep-alive"))
        assertNull(SseFrameParser.payloadOf(":"))
    }

    @Test
    fun `skips the done sentinel`() {
        assertNull(SseFrameParser.payloadOf("data: [DONE]"))
        assertTrue(SseFrameParser.isDone("data: [DONE]"))
        assertFalse(SseFrameParser.isDone("""data: {"cravingProbability":0.6}"""))
    }

    @Test
    fun `skips blank lines and non-data fields`() {
        assertNull(SseFrameParser.payloadOf(""))
        assertNull(SseFrameParser.payloadOf("   "))
        assertNull(SseFrameParser.payloadOf("event: prediction"))
        assertNull(SseFrameParser.payloadOf("id: 42"))
        assertNull(SseFrameParser.payloadOf("retry: 3000"))
        assertNull(SseFrameParser.payloadOf("data:"))
    }

    @Test
    fun `parses a prediction frame`() {
        val frame = SseFrameParser.payloadOf(
            """data: {"predictionId":"p-1","cravingProbability":0.6,"timestampMs":1784160000000,""" +
                """"source":"watch_sensor","alertId":"a-1","alertAction":"recommend","sequence":7}""",
        )
        val prediction = PredictionPayloadParser.parse(frame)

        assertNotNull(prediction)
        assertEquals("p-1", prediction!!.predictionId)
        assertEquals(0.6f, prediction.cravingProbability, 1e-6f)
        assertEquals(1_784_160_000_000L, prediction.timestampMs)
        assertEquals("a-1", prediction.alertId)
        assertEquals(7L, prediction.sequence)
        // p = 0.60 is 주의, not 심각 — the stage comes from the probability, never from the class.
        assertEquals(CravingStage.CAUTION, prediction.stage)
        assertTrue(AlertActionPolicy.recommendsConversation(prediction.alertAction))
    }

    @Test
    fun `parses a prediction nested in an upload response`() {
        val prediction = PredictionPayloadParser.parse(
            """{"clientWindowId":"w-1","prediction":{"id":"p-2","cravingProbability":0.1,""" +
                """"timestampMs":10,"source":"watch_sensor"}}""",
        )
        assertEquals("p-2", prediction?.predictionId)
        assertEquals(CravingStage.SAFE, prediction?.stage)
    }

    @Test
    fun `rejects a frame with no probability or an out-of-range one`() {
        assertNull(PredictionPayloadParser.parse("""{"predictionId":"p-3"}"""))
        assertNull(PredictionPayloadParser.parse("""{"cravingProbability":1.4}"""))
        assertNull(PredictionPayloadParser.parse("""{"cravingProbability":-0.1}"""))
        assertNull(PredictionPayloadParser.parse("not json"))
        assertNull(PredictionPayloadParser.parse(""))
        assertNull(PredictionPayloadParser.parse(null))
    }

    @Test
    fun `deduplicates the same prediction arriving on both transports`() {
        val ledger = PredictionLedger()
        val frame = """{"predictionId":"p-9","cravingProbability":0.8,"timestampMs":1,""" +
            """"source":"watch_sensor","alertId":"a-9","alertAction":"required"}"""

        val fromUpload = PredictionPayloadParser.parse(frame)!!
        val fromStream = PredictionPayloadParser.parse(frame)!!

        assertTrue(ledger.claimPrediction(fromUpload))
        assertFalse(ledger.claimPrediction(fromStream))

        assertTrue(ledger.claimAlert(fromUpload.alertId))
        assertFalse(ledger.claimAlert(fromStream.alertId))
    }

    @Test
    fun `an alert with no id is never presentable`() {
        val ledger = PredictionLedger()
        assertFalse(ledger.claimAlert(null))
        assertFalse(ledger.claimAlert(""))
    }

    @Test
    fun `the watch payload carries stage and alert metadata but no model or raw signal values`() {
        val prediction = PredictionPayloadParser.parse(
            """{"predictionId":"p-4","cravingProbability":0.91,"timestampMs":5,""" +
                """"source":"watch_sensor","alertId":"a-4","alertAction":"required",""" +
                """"classProbabilities":[0.09,0.91],"PPG_GREEN":[1,2],"EDA":[3]}""",
        )!!
        val relayed = PredictionPayloadParser.toWatchPayload(prediction)

        assertFalse(relayed.contains("cravingProbability"))
        assertFalse(relayed.contains("classProbabilities"))
        assertFalse(relayed.contains(""""class""""))
        assertFalse(relayed.contains("PPG_GREEN"))
        assertFalse(relayed.contains("EDA"))
        assertFalse(relayed.contains("0.91"))
        assertTrue(relayed.contains(""""stageCode":"high""""))
        assertTrue(relayed.contains(""""alertId":"a-4""""))
        assertTrue(relayed.contains(""""hasAlertMetadata":true"""))
    }

    @Test
    fun `a camera result carries the camera source so it is never relayed`() {
        val prediction = PredictionPayloadParser.parse(
            """{"predictionId":"p-5","cravingProbability":0.4,"timestampMs":5,"source":"camera_rppg"}""",
        )!!
        assertEquals(SOURCE_CAMERA, prediction.source)
    }
}
