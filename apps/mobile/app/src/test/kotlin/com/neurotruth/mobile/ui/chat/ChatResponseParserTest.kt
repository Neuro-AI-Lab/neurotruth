package com.neurotruth.mobile.ui.chat

import com.neurotruth.mobile.core.net.ApiResponse
import com.neurotruth.mobile.core.INPUT_MODALITY_TEXT
import com.neurotruth.mobile.core.INPUT_MODALITY_VOICE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponseParserTest {

    @Test
    fun `fastapi detail payload controls retry state`() {
        val failure = requireNotNull(
            dialogueFailure(
                ApiResponse(
                    502,
                    """
                    {"detail":{"code":"dialogue_provider_error",
                    "clientMessageId":"client-1","userMessageId":"user-1",
                    "retryable":false,"attemptsRemaining":0}}
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals("dialogue_provider_error", failure.code)
        assertEquals("client-1", failure.clientMessageId)
        assertEquals("user-1", failure.userMessageId)
        assertFalse(failure.retryable)
        assertEquals(0, failure.attemptsRemaining)
    }

    @Test
    fun `non gateway response is not a dialogue failure`() {
        assertNull(dialogueFailure(ApiResponse(409, """{"detail":{"code":"conflict"}}""")))
    }

    @Test
    fun `voice reply is auto read even when text auto read is disabled`() {
        assertTrue(shouldAutoReadReply(INPUT_MODALITY_VOICE, autoReadEnabled = false))
        assertFalse(shouldAutoReadReply(INPUT_MODALITY_TEXT, autoReadEnabled = false))
        assertTrue(shouldAutoReadReply(INPUT_MODALITY_TEXT, autoReadEnabled = true))
    }
}
