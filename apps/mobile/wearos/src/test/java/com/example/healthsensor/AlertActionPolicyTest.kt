package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlertActionPolicyTest {
    @Test
    fun `legacy class two is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BinaryPredictionContract.requireClass(2)
        }
    }

    @Test
    fun `class one alone never triggers a local alert`() {
        assertEquals(
            AlertAction.NONE,
            AlertActionPolicy.resolve(
                alertAction = null,
                alertLevel = null,
                hasAlertMetadata = false,
                cravingClass = 1
            )
        )
    }

    @Test
    fun `server action takes precedence over class`() {
        assertEquals(
            AlertAction.REQUIRED,
            AlertActionPolicy.resolve(
                alertAction = "required_intervention",
                alertLevel = "none",
                hasAlertMetadata = true,
                cravingClass = 0
            )
        )
        assertEquals(
            AlertAction.COOLDOWN,
            AlertActionPolicy.resolve(
                alertAction = "cooldown",
                alertLevel = "required",
                hasAlertMetadata = true,
                cravingClass = 1
            )
        )
    }

    @Test
    fun `server level is used only when action is absent`() {
        assertEquals(
            AlertAction.RECOMMEND,
            AlertActionPolicy.resolve(
                alertAction = null,
                alertLevel = "recommend",
                hasAlertMetadata = true,
                cravingClass = 0
            )
        )
    }
}
