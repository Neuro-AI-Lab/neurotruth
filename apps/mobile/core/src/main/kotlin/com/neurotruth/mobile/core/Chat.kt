package com.neurotruth.mobile.core

const val INPUT_MODALITY_TEXT: String = "text"
const val INPUT_MODALITY_VOICE: String = "voice"

fun requireInputModality(value: String): String {
    require(value == INPUT_MODALITY_TEXT || value == INPUT_MODALITY_VOICE) {
        "inputModality must be '$INPUT_MODALITY_TEXT' or '$INPUT_MODALITY_VOICE'"
    }
    return value
}

/** Sanitized 502 from the dialogue provider. */
data class DialogueFailure(
    val code: String,
    val clientMessageId: String?,
    val userMessageId: String?,
    val retryable: Boolean,
    val attemptsRemaining: Int,
)

data class PendingChatRetry(
    val clientMessageId: String,
    val content: String,
    val inputModality: String,
    val attemptsRemaining: Int,
)

/**
 * A provider failure keeps the user's bubble and offers exactly one manual retry with the same id,
 * body and modality. Editing the body is a new message with a new id; the app never regenerates an
 * id on a 409.
 */
object ChatRetryPolicy {
    fun pending(
        failure: DialogueFailure?,
        clientMessageId: String,
        content: String,
        inputModality: String = INPUT_MODALITY_TEXT,
    ): PendingChatRetry? {
        if (failure == null) return null
        if (!failure.retryable || failure.attemptsRemaining <= 0) return null
        requireInputModality(inputModality)
        return PendingChatRetry(
            clientMessageId = clientMessageId,
            content = content,
            inputModality = inputModality,
            attemptsRemaining = failure.attemptsRemaining,
        )
    }
}

/** Mirrors the server's inactivity timeout so a long reply is not cut off by the client. */
object ChatReadTimeoutPolicy {
    const val DEFAULT_MINUTES: Int = 60
    const val MIN_MINUTES: Int = 1
    const val MAX_MINUTES: Int = 1_440

    fun isValid(minutes: Int): Boolean = minutes in MIN_MINUTES..MAX_MINUTES

    fun storedOrDefault(minutes: Int?): Int =
        if (minutes != null && isValid(minutes)) minutes else DEFAULT_MINUTES

    fun toMillis(minutes: Int): Int {
        require(isValid(minutes)) { "minutes must be within $MIN_MINUTES..$MAX_MINUTES" }
        return minutes * 60_000
    }
}
