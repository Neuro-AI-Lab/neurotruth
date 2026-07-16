package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadFailurePolicyTest {
    @Test
    fun authorizationFailureRequiresAuthentication() {
        assertEquals(
            UploadFailureAction.AUTHENTICATION_REQUIRED,
            UploadFailurePolicy.resolve(401)
        )
    }

    @Test
    fun consentAndOtherTerminalClientFailuresPauseAndDrop() {
        for (status in listOf(400, 403, 413, 415, 422)) {
            assertEquals(
                UploadFailureAction.PAUSE_AND_DROP,
                UploadFailurePolicy.resolve(status)
            )
        }
    }

    @Test
    fun idempotencyConflictDropsOnlyTheConflictingWindow() {
        assertEquals(
            UploadFailureAction.DROP_AND_CONTINUE,
            UploadFailurePolicy.resolve(409)
        )
    }

    @Test
    fun networkAndServerFailuresRetryTheExactSamePayload() {
        for (status in listOf(-1, 429, 500, 503)) {
            assertEquals(
                UploadFailureAction.RETRY_SAME_PAYLOAD,
                UploadFailurePolicy.resolve(status)
            )
        }
    }
}
