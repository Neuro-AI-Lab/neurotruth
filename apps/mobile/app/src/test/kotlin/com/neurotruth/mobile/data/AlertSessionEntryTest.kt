package com.neurotruth.mobile.data

import com.neurotruth.mobile.core.SessionEntryPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertSessionEntryTest {

    @After
    fun clear() = AlertSessionEntry.clear()

    @Test
    fun `alert entry preserves its trigger exactly once`() {
        AlertSessionEntry.arm("alert-id")

        assertEquals(SessionEntryPoint.ALERT to "alert-id", AlertSessionEntry.consume())
        assertEquals(SessionEntryPoint.CHAT_TAB to null, AlertSessionEntry.consume())
    }

    @Test
    fun `rppg completion is carried into chat exactly once`() {
        AlertSessionEntry.arm(SessionEntryPoint.RPPG_COMPLETION)

        val (entryPoint, alertId) = AlertSessionEntry.consume()
        assertEquals(SessionEntryPoint.RPPG_COMPLETION, entryPoint)
        assertNull(alertId)
        assertEquals(SessionEntryPoint.CHAT_TAB to null, AlertSessionEntry.consume())
    }
}
