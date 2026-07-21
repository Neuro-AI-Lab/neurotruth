package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPredictionParserTest {
    private val uploader = ServerUploader()

    @Test
    fun parsesBinaryProbabilitiesAndServerAlertAction() {
        val prediction = uploader.parsePrediction(
            """{
              "predictionSchema":"binary-craving-v1",
              "predictionId":"11111111-1111-4111-8111-111111111111",
              "class":1,
              "classCode":"high",
              "confidence":0.812345,
              "cravingProbability":0.812345,
              "classProbabilities":{"low":0.187655,"high":0.812345},
              "timestampMs":1784160000000,
              "alertLevel":"recommend",
              "alertAction":"recommend_intervention",
              "classOneRatio":0.7
            }"""
        )

        assertEquals(1, prediction.cravingClass)
        assertEquals("1 높음", prediction.label)
        assertEquals(0.812345f, prediction.cravingProbability)
        assertEquals(0.187655f, prediction.classProbabilities["low"])
        assertEquals(0.7f, prediction.alert.classOneRatio)
        assertEquals(AlertAction.RECOMMEND, AlertActionPolicy.resolve(prediction))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLegacyClassTwo() {
        uploader.parsePrediction(
            """{"predictionSchema":"binary-craving-v1","class":2,"confidence":0.9,"cravingProbability":0.9,"classProbabilities":{"low":0.1,"high":0.9}}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingBinarySchema() {
        uploader.parsePrediction(
            """{"class":1,"confidence":0.9,"cravingProbability":0.9,"classProbabilities":{"low":0.1,"high":0.9}}"""
        )
    }

    @Test
    fun classOnlyCannotTriggerLocalAlert() {
        assertTrue(
            AlertActionPolicy.resolve(null, null, false, cravingClass = 1).suppressesUserFacingActions
        )
    }
}
