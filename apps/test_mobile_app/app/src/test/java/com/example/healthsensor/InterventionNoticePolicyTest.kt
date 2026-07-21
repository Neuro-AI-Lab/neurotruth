package com.example.healthsensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionNoticePolicyTest {
    @Test
    fun firstUseAndChangedVersionRequireAcknowledgement() {
        val store = MemoryNoticeStore()
        val first = InterventionNoticePolicy(store, "v1")

        assertTrue(first.requiresAcknowledgement())
        assertTrue(first.acknowledge())
        assertFalse(first.requiresAcknowledgement())
        assertTrue(InterventionNoticePolicy(store, "v2").requiresAcknowledgement())
    }
}

private class MemoryNoticeStore : NoticeVersionStore {
    private var value: String? = null
    override fun load(): String? = value
    override fun save(version: String): Boolean { value = version; return true }
}
