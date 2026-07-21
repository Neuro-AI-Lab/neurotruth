package com.example.healthsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.ArrayDeque

class StableClientWindowIdStoreTest {
    @Test
    fun retryAndStoreRecreationReuseThePersistedUuidUntilCompletion() {
        val ids = ArrayDeque(
            listOf(
                "e76a4756-e513-40aa-b152-8717118a815e",
                "d9425ca8-57b2-4ff0-bf5d-d5df23b60598"
            )
        )
        val storage = InMemoryStringStore()
        val first = StableClientWindowIdStore(storage) { ids.removeFirst() }

        val initial = first.getOrCreate("session-1:sequence-42")
        val retry = first.getOrCreate("session-1:sequence-42")
        val afterProcessRestart = StableClientWindowIdStore(storage) { ids.removeFirst() }
            .getOrCreate("session-1:sequence-42")

        assertEquals(initial, retry)
        assertEquals(initial, afterProcessRestart)

        first.markCompleted("session-1:sequence-42")
        val next = first.getOrCreate("session-1:sequence-42")
        assertNotEquals(initial, next)
    }
}
