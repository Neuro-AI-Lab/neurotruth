package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePredictionSenderTest {
    private val requiredPrediction = CravingPrediction(
        cravingClass = 2,
        timestampMs = 1_725_000_000_123L,
        rawBody = "raw",
        score = 0.91f,
        confidence = 0.82f,
        sequence = 17L,
        sessionId = "session-123",
        alert = AlertMetadata(
            alertLevel = "required",
            alertAction = "required_intervention",
            windowMean = 0.73f,
            triggerReason = "threshold",
            alertRequired = true,
            isPresent = true
        )
    )

    @Test
    fun activeInterventionOverridesOnlyAlertActionAndPreservesMetadata() {
        val original = PhonePredictionSender.buildPayloadFields(requiredPrediction)
        val suppressed = PhonePredictionSender.buildPayloadFields(
            prediction = requiredPrediction,
            suppressAlertPresentation = true
        )

        assertEquals("none", suppressed["alertAction"])
        assertEquals(original.keys, suppressed.keys)
        original.filterKeys { it != "alertAction" }.forEach { (key, value) ->
            assertEquals("field $key", value, suppressed[key])
        }
        assertEquals(2, suppressed["class"])
        assertEquals("session-123", suppressed["sessionId"])
        assertEquals(17L, suppressed["sequence"])
        assertEquals(true, suppressed["hasAlertMetadata"])
        assertEquals("required", suppressed["alertLevel"])
        assertEquals(true, suppressed["alertRequired"])
        assertEquals("threshold", suppressed["triggerReason"])
    }

    @Test
    fun inactiveInterventionPreservesOriginalAlertActionAndShape() {
        val payload = PhonePredictionSender.buildPayloadFields(
            prediction = requiredPrediction,
            suppressAlertPresentation = false
        )

        assertEquals("required_intervention", payload["alertAction"])
        assertEquals(
            setOf(
                "class",
                "timestampMs",
                "hasAlertMetadata",
                "sessionId",
                "score",
                "confidence",
                "sequence",
                "alertLevel",
                "alertRequired",
                "alertAction",
                "windowMean",
                "triggerReason"
            ),
            payload.keys
        )
    }

    @Test
    fun inactiveInterventionDoesNotAddMissingAlertAction() {
        val predictionWithoutAction = requiredPrediction.copy(
            alert = requiredPrediction.alert.copy(alertAction = null)
        )

        val payload = PhonePredictionSender.buildPayloadFields(predictionWithoutAction)

        assertFalse(payload.containsKey("alertAction"))
        assertTrue(payload["hasAlertMetadata"] as Boolean)
        assertEquals("required", payload["alertLevel"])
    }

    @Test
    fun activeInterventionAddsExplicitNoneWithoutAlertMetadata() {
        val predictionWithoutMetadata = requiredPrediction.copy(alert = AlertMetadata())

        val payload = PhonePredictionSender.buildPayloadFields(
            prediction = predictionWithoutMetadata,
            suppressAlertPresentation = true
        )

        assertEquals("none", payload["alertAction"])
        assertEquals(false, payload["hasAlertMetadata"])
        assertFalse(payload.containsKey("alertLevel"))
        assertFalse(payload.containsKey("alertRequired"))
    }
}
