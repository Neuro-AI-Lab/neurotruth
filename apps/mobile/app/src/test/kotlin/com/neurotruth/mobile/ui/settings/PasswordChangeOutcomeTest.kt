package com.neurotruth.mobile.ui.settings

import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.AuthenticationRequiredException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NT-09 password change.
 *
 * The point of the whole mapping is that a wrong current password and a dead session are different
 * outcomes. Before `treatUnauthorizedAsResponse` existed they were indistinguishable and a typo
 * signed the user out, so both halves are pinned here.
 */
class PasswordChangeOutcomeTest {

    @Test
    fun `204 is a completed change`() {
        assertEquals(PasswordChangeOutcome.CHANGED, PasswordChangeOutcomePolicy.of(204))
    }

    @Test
    fun `401 returned as a response is a wrong current password, not a lost session`() {
        val outcome = PasswordChangeOutcomePolicy.of(401)
        assertEquals(PasswordChangeOutcome.WRONG_CURRENT_PASSWORD, outcome)
        assertTrue(PasswordChangeOutcomePolicy.isCurrentPasswordRejected(outcome))
        assertFalse("a typo must never sign the user out", PasswordChangeOutcomePolicy.signsOut(outcome))
    }

    @Test
    fun `a propagated AuthenticationRequiredException is a lost session`() {
        val outcome = PasswordChangeOutcomePolicy.of(AuthenticationRequiredException())
        assertEquals(PasswordChangeOutcome.SESSION_EXPIRED, outcome)
        assertTrue(PasswordChangeOutcomePolicy.signsOut(outcome))
        assertFalse(PasswordChangeOutcomePolicy.isCurrentPasswordRejected(outcome))
    }

    @Test
    fun `a completed change signs out because the server revoked every session`() {
        assertTrue(PasswordChangeOutcomePolicy.signsOut(PasswordChangeOutcome.CHANGED))
    }

    @Test
    fun `other statuses and transport errors are plain failures that keep the user signed in`() {
        assertEquals(PasswordChangeOutcome.FAILED, PasswordChangeOutcomePolicy.of(422))
        assertEquals(PasswordChangeOutcome.FAILED, PasswordChangeOutcomePolicy.of(503))
        assertEquals(PasswordChangeOutcome.FAILED, PasswordChangeOutcomePolicy.of(IOException("timeout")))
        assertFalse(PasswordChangeOutcomePolicy.signsOut(PasswordChangeOutcome.FAILED))
    }

    @Test
    fun `an ApiHttpException carrying 401 is still a wrong current password`() {
        assertEquals(
            PasswordChangeOutcome.WRONG_CURRENT_PASSWORD,
            PasswordChangeOutcomePolicy.of(ApiHttpException(401, "")),
        )
    }
}
