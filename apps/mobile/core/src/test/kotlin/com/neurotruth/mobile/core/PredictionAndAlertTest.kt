package com.neurotruth.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionAndAlertTest {

    private fun prediction(timestampMs: Long, source: String, probability: Float = 0.4f) =
        CravingPrediction(probability, timestampMs, source)

    @Test
    fun `a delayed older camera result never replaces a newer watch result`() {
        val watch = prediction(2_000L, SOURCE_WATCH)
        val staleCamera = prediction(1_000L, SOURCE_CAMERA)
        assertFalse(LatestPredictionPolicy.shouldReplace(watch, staleCamera))
    }

    @Test
    fun `a newer camera result does replace an older watch result`() {
        val watch = prediction(1_000L, SOURCE_WATCH)
        val camera = prediction(2_000L, SOURCE_CAMERA)
        assertTrue(LatestPredictionPolicy.shouldReplace(watch, camera))
    }

    @Test
    fun `watch wins an exact timestamp tie`() {
        val camera = prediction(1_000L, SOURCE_CAMERA)
        val watch = prediction(1_000L, SOURCE_WATCH)
        assertTrue(LatestPredictionPolicy.shouldReplace(camera, watch))
        assertFalse(LatestPredictionPolicy.shouldReplace(watch, camera))
    }

    @Test
    fun `the first prediction is always adopted`() {
        assertTrue(LatestPredictionPolicy.shouldReplace(null, prediction(1L, SOURCE_CAMERA)))
    }

    @Test
    fun `only a recognized alert action presents an alert`() {
        assertEquals(AlertAction.RECOMMEND, AlertActionPolicy.resolve("recommend_intervention"))
        assertEquals(AlertAction.RECOMMEND, AlertActionPolicy.resolve("  Recommend  "))
        assertEquals(AlertAction.REQUIRED, AlertActionPolicy.resolve("required"))
        assertEquals(AlertAction.COOLDOWN, AlertActionPolicy.resolve("cooldown"))
        assertEquals(AlertAction.NONE, AlertActionPolicy.resolve("none"))
    }

    @Test
    fun `an unknown or missing action never escalates`() {
        assertEquals(AlertAction.NONE, AlertActionPolicy.resolve(null))
        assertEquals(AlertAction.NONE, AlertActionPolicy.resolve(""))
        assertEquals(AlertAction.NONE, AlertActionPolicy.resolve("high"))
        assertEquals(AlertAction.NONE, AlertActionPolicy.resolve("class_1"))
    }

    @Test
    fun `none and cooldown suppress user facing actions`() {
        assertTrue(AlertAction.NONE.suppressesUserFacingActions)
        assertTrue(AlertAction.COOLDOWN.suppressesUserFacingActions)
        assertFalse(AlertAction.RECOMMEND.suppressesUserFacingActions)
        assertFalse(AlertAction.REQUIRED.suppressesUserFacingActions)
    }

    @Test
    fun `the same alert id is claimed once across sse and upload response`() {
        val store = AlertClaimStore()
        assertTrue(store.claim("alert-1"))
        assertFalse(store.claim("alert-1"))
        assertTrue(store.claim("alert-2"))
    }

    @Test
    fun `home shows the conversation prompt only for recommend or required`() {
        assertTrue(AlertActionPolicy.recommendsConversation("recommend"))
        assertTrue(AlertActionPolicy.recommendsConversation("required_intervention"))
        assertFalse(AlertActionPolicy.recommendsConversation("cooldown"))
        assertFalse(AlertActionPolicy.recommendsConversation(null))
    }

    @Test
    fun `measurement origin distinguishes live watch from a stored camera result`() {
        assertEquals("실시간 · Watch", MeasurementOriginLabel.LIVE_WATCH)
        assertEquals(
            "2026-07-21 14:05 · 카메라",
            MeasurementOriginLabel.stored("2026-07-21 14:05", SOURCE_CAMERA),
        )
        assertEquals(
            "2026-07-21 14:05 · Watch",
            MeasurementOriginLabel.stored("2026-07-21 14:05", SOURCE_WATCH),
        )
    }
}
