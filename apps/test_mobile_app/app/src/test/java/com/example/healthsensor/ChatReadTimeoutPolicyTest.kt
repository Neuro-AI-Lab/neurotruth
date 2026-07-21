package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadTimeoutPolicyTest {
    @Test
    fun absentOrInvalidStoredValueFallsBackToSixtyMinutes() {
        assertEquals(60, ChatReadTimeoutPolicy.storedOrDefault(Int.MIN_VALUE))
        assertEquals(60, ChatReadTimeoutPolicy.storedOrDefault(0))
        assertEquals(60, ChatReadTimeoutPolicy.storedOrDefault(1_441))
    }

    @Test
    fun acceptsInclusiveAdministratorRangeAndConvertsToMillis() {
        assertTrue(ChatReadTimeoutPolicy.isValid(1))
        assertTrue(ChatReadTimeoutPolicy.isValid(1_440))
        assertFalse(ChatReadTimeoutPolicy.isValid(0))
        assertFalse(ChatReadTimeoutPolicy.isValid(1_441))
        assertEquals(60_000, ChatReadTimeoutPolicy.toMillis(1))
        assertEquals(86_400_000, ChatReadTimeoutPolicy.toMillis(1_440))
    }
}
