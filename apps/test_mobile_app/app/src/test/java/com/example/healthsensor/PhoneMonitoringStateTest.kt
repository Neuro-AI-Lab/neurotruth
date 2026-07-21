package com.example.healthsensor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMonitoringStateTest {
    @After
    fun restoreFreshSession() {
        PhoneMonitoringState.startNewSession()
    }

    @Test
    fun resetCreatesSharedSessionAndClearsSessionScopedState() {
        PhoneMonitoringState.startNewSession(startedAtMs = 100L, sessionId = "local-1")
        val prediction = CravingPrediction(
            cravingClass = 2,
            timestampMs = 101L,
            rawBody = "class-only",
            sessionId = "server-1"
        )
        PhoneMonitoringState.publishPrediction(prediction)
        PhoneMonitoringState.markStateCheckSubmitted(102L)
        assertTrue(PhoneMonitoringState.registerAlertAction(prediction, AlertAction.REQUIRED))
        assertFalse(PhoneMonitoringState.registerAlertAction(prediction, AlertAction.REQUIRED))
        PhoneMonitoringState.recordUploadLatency(
            sessionId = "local-1",
            attemptStartedAtMs = 103L,
            completedAtMs = 110L
        )
        assertTrue(PhoneMonitoringState.uploadLatencySnapshot().isNotEmpty())
        assertEquals(7f, PhoneMonitoringState.uploadLatencySnapshot().single().second)
        assertEquals("server-1", PhoneMonitoringState.interventionSessionId())

        val previous = PhoneMonitoringState.currentSession()
        val current = PhoneMonitoringState.startNewSession(
            startedAtMs = 200L,
            sessionId = "local-2"
        )

        assertNotEquals(previous.sessionId, current.sessionId)
        assertEquals("local-2", current.sessionId)
        assertEquals(200L, current.startedAtMs)
        assertEquals("local-2", PhoneMonitoringState.interventionSessionId())
        assertNull(PhoneMonitoringState.latestServerSessionId.value)
        assertNull(PhoneMonitoringState.latestPrediction.value)
        assertEquals(0L, PhoneMonitoringState.lastStateCheckSubmittedAtMs.value)
        assertEquals(0L, PhoneMonitoringState.alertActionVersion.value)
        assertEquals(AlertAction.NONE, PhoneMonitoringState.presentedAlertAction.value)
        assertFalse(PhoneMonitoringState.isInterventionActive.value)
        assertTrue(PhoneMonitoringState.uploadLatencySnapshot().isEmpty())
        assertTrue(
            PhoneMonitoringState.registerAlertAction(
                CravingPrediction(
                    cravingClass = 2,
                    timestampMs = 201L,
                    rawBody = "new-session-event"
                ),
                AlertAction.REQUIRED
            )
        )
    }

    @Test
    fun claimsSameEventOnceButAllowsLaterEventWithSameAction() {
        PhoneMonitoringState.startNewSession(startedAtMs = 100L, sessionId = "local-1")
        val sequenced = CravingPrediction(
            cravingClass = 2,
            timestampMs = 1_000L,
            rawBody = "sequenced",
            sequence = 7L,
            sessionId = "server-1"
        )

        assertTrue(PhoneMonitoringState.registerAlertAction(sequenced, AlertAction.REQUIRED))
        assertFalse(
            PhoneMonitoringState.registerAlertAction(
                sequenced.copy(timestampMs = 9_000L),
                AlertAction.REQUIRED
            )
        )
        assertTrue(
            PhoneMonitoringState.registerAlertAction(
                sequenced.copy(sequence = 8L),
                AlertAction.REQUIRED
            )
        )

        val unsequenced = CravingPrediction(
            cravingClass = 1,
            timestampMs = 20_000L,
            rawBody = "unsequenced",
            sessionId = "server-1"
        )
        assertTrue(PhoneMonitoringState.registerAlertAction(unsequenced, AlertAction.RECOMMEND))
        assertFalse(PhoneMonitoringState.registerAlertAction(unsequenced, AlertAction.RECOMMEND))
        assertTrue(
            PhoneMonitoringState.registerAlertAction(
                unsequenced.copy(timestampMs = 80_000L),
                AlertAction.RECOMMEND
            )
        )
        assertFalse(
            PhoneMonitoringState.registerAlertAction(
                unsequenced.copy(timestampMs = 90_000L),
                AlertAction.NONE
            )
        )
        assertFalse(
            PhoneMonitoringState.registerAlertAction(
                unsequenced.copy(timestampMs = 100_000L),
                AlertAction.COOLDOWN
            )
        )
    }

    @Test
    fun activeInterventionSuppressesPresentationButClaimsAlert() {
        PhoneMonitoringState.startNewSession(startedAtMs = 100L, sessionId = "local-1")
        PhoneMonitoringState.activateIntervention()
        val prediction = CravingPrediction(
            cravingClass = 2,
            timestampMs = 1_000L,
            rawBody = "active-intervention",
            sequence = 7L
        )

        assertFalse(PhoneMonitoringState.registerAlertAction(prediction, AlertAction.REQUIRED))
        assertEquals(AlertAction.REQUIRED, PhoneMonitoringState.presentedAlertAction.value)
        assertEquals(0L, PhoneMonitoringState.alertActionVersion.value)

        PhoneMonitoringState.closeIntervention()
        assertFalse(PhoneMonitoringState.registerAlertAction(prediction, AlertAction.REQUIRED))
        assertTrue(
            PhoneMonitoringState.registerAlertAction(
                prediction.copy(sequence = 8L),
                AlertAction.REQUIRED
            )
        )
        assertEquals(1L, PhoneMonitoringState.alertActionVersion.value)
    }

    @Test
    fun closeAndSessionResetClearInterventionLatch() {
        PhoneMonitoringState.activateIntervention()
        assertTrue(PhoneMonitoringState.isInterventionActive.value)

        PhoneMonitoringState.closeIntervention()
        assertFalse(PhoneMonitoringState.isInterventionActive.value)

        PhoneMonitoringState.activateIntervention()
        PhoneMonitoringState.startNewSession(startedAtMs = 300L, sessionId = "reset")
        assertFalse(PhoneMonitoringState.isInterventionActive.value)
    }
}
