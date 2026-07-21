package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertActionPolicyTest {
    @Test
    fun recognizedActionTakesPrecedenceOverLevelAndClass() {
        val action = AlertActionPolicy.resolve(
            alertAction = "cooldown",
            alertLevel = "required",
            hasAlertMetadata = true,
            cravingClass = 2
        )

        assertEquals(AlertAction.COOLDOWN, action)
        assertTrue(action.suppressesUserFacingActions)
    }

    @Test
    fun backendInterventionActionsTakePrecedenceOverConflictingLevels() {
        assertEquals(
            AlertAction.RECOMMEND,
            AlertActionPolicy.resolve(
                alertAction = "recommend_intervention",
                alertLevel = "required",
                hasAlertMetadata = true,
                cravingClass = 2
            )
        )
        assertEquals(
            AlertAction.REQUIRED,
            AlertActionPolicy.resolve(
                alertAction = "required_intervention",
                alertLevel = "recommend",
                hasAlertMetadata = true,
                cravingClass = 1
            )
        )
    }

    @Test
    fun unknownActionDoesNotFallThroughToLevelOrClass() {
        assertEquals(
            AlertAction.NONE,
            AlertActionPolicy.resolve(
                alertAction = "future-action",
                alertLevel = "recommend",
                hasAlertMetadata = true,
                cravingClass = 2
            )
        )
    }

    @Test
    fun metadataPreventsRawClassFallback() {
        assertEquals(
            AlertAction.NONE,
            AlertActionPolicy.resolve(
                alertAction = "future-action",
                alertLevel = "future-level",
                hasAlertMetadata = true,
                cravingClass = 2
            )
        )
    }

    @Test
    fun noneAndCooldownSuppressUserFacingActions() {
        assertTrue(AlertAction.NONE.suppressesUserFacingActions)
        assertTrue(AlertAction.COOLDOWN.suppressesUserFacingActions)
        assertFalse(AlertAction.RECOMMEND.suppressesUserFacingActions)
        assertFalse(AlertAction.REQUIRED.suppressesUserFacingActions)
    }

    @Test
    fun classOnlyPayloadNeverTriggersBinaryAlert() {
        assertEquals(AlertAction.NONE, resolveLegacy(0))
        assertEquals(AlertAction.NONE, resolveLegacy(1))
        assertEquals(AlertAction.NONE, resolveLegacy(2))
    }

    private fun resolveLegacy(cravingClass: Int): AlertAction =
        AlertActionPolicy.resolve(
            alertAction = null,
            alertLevel = null,
            hasAlertMetadata = false,
            cravingClass = cravingClass
        )
}
