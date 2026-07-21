package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRetryPolicyTest {
    private val clientMessageId = "6b38d6d4-b27f-4d61-bf7d-4579ca4a4bdf"

    @Test
    fun firstProviderFailureKeepsSameMessageForOneManualRetry() {
        val pending = ChatRetryPolicy.pending(
            DialogueRequestException(
                code = "dialogue_provider_error",
                clientMessageId = clientMessageId,
                userMessageId = "77777777-7777-4777-8777-777777777777",
                retryable = true,
                attemptsRemaining = 1
            ),
            clientMessageId,
            "같은 사용자 메시지"
        )

        assertEquals(clientMessageId, pending?.clientMessageId)
        assertEquals("같은 사용자 메시지", pending?.content)
        assertEquals(1, pending?.attemptsRemaining)
    }

    @Test
    fun exhaustedOrUnrelatedFailureDoesNotShowRetry() {
        assertNull(
            ChatRetryPolicy.pending(
                DialogueRequestException(
                    code = "dialogue_output_rejected",
                    clientMessageId = clientMessageId,
                    userMessageId = null,
                    retryable = false,
                    attemptsRemaining = 0
                ),
                clientMessageId,
                "메시지"
            )
        )
        assertNull(ChatRetryPolicy.pending(ApiHttpException(409), clientMessageId, "메시지"))
    }
}
