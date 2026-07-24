package com.neurotruth.mobile.ui.settings

import com.neurotruth.mobile.core.net.ApiHttpException
import com.neurotruth.mobile.core.net.AuthenticationRequiredException

/** What `POST /api/auth/change-password` actually meant. */
enum class PasswordChangeOutcome {
    /** 204. The server revoked every session, so the user has to sign in again. */
    CHANGED,

    /** 401 that survived a successful refresh: the *current password* is wrong, not the session. */
    WRONG_CURRENT_PASSWORD,

    /** The session really is gone — the refresh itself failed. */
    SESSION_EXPIRED,

    /** Anything else: input rejected, server trouble, no network. */
    FAILED,
}

/**
 * The three outcomes of NT-09's password change, kept pure so they can be pinned without Android.
 *
 * The request is sent with `treatUnauthorizedAsResponse = true`, so
 * [com.neurotruth.mobile.core.net.AuthenticatedApiClient] still refreshes and replays — a genuinely
 * expired token is recovered — but hands back the endpoint's own 401 instead of clearing the
 * session. That is what makes [WRONG_CURRENT_PASSWORD] and [SESSION_EXPIRED] distinguishable at
 * all; before the flag existed a typo signed the user out.
 */
object PasswordChangeOutcomePolicy {

    const val HTTP_UNAUTHORIZED: Int = 401

    fun of(statusCode: Int): PasswordChangeOutcome = when {
        statusCode in 200..299 -> PasswordChangeOutcome.CHANGED
        statusCode == HTTP_UNAUTHORIZED -> PasswordChangeOutcome.WRONG_CURRENT_PASSWORD
        else -> PasswordChangeOutcome.FAILED
    }

    fun of(error: Throwable): PasswordChangeOutcome = when {
        error is AuthenticationRequiredException -> PasswordChangeOutcome.SESSION_EXPIRED
        error is ApiHttpException -> of(error.statusCode)
        else -> PasswordChangeOutcome.FAILED
    }

    /** Only a successful change and a dead session end the local session. */
    fun signsOut(outcome: PasswordChangeOutcome): Boolean =
        outcome == PasswordChangeOutcome.CHANGED || outcome == PasswordChangeOutcome.SESSION_EXPIRED

    /** A wrong current password is a field-level error on 현재 비밀번호, not a screen-level one. */
    fun isCurrentPasswordRejected(outcome: PasswordChangeOutcome): Boolean =
        outcome == PasswordChangeOutcome.WRONG_CURRENT_PASSWORD
}
