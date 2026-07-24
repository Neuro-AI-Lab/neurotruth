package com.neurotruth.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEntryTest {

    @Test
    fun `alert entry is the only one carrying a trigger alert id`() {
        val alert = SessionStartRequest.of(SessionEntryPoint.ALERT, "6f1c3a5e-alert")
        assertEquals("alert_checkin", alert.sessionType)
        assertEquals("6f1c3a5e-alert", alert.triggerAlertId)

        val chat = SessionStartRequest.of(SessionEntryPoint.CHAT_TAB)
        assertEquals("manual_checkin", chat.sessionType)
        assertNull(chat.triggerAlertId)

        val rppg = SessionStartRequest.of(SessionEntryPoint.RPPG_COMPLETION)
        assertEquals("manual_checkin", rppg.sessionType)
        assertNull(rppg.triggerAlertId)
    }

    @Test
    fun `a non-alert entry cannot smuggle an alert id`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionStartRequest.of(SessionEntryPoint.CHAT_TAB, "6f1c3a5e-alert")
        }
    }

    @Test
    fun `a new session is identified by the servers opening invitation`() {
        assertTrue(SessionCreationPolicy.wasCreated("지금 상황이나 원하는 도움을 편하게 말씀해 주세요."))
        assertFalse(SessionCreationPolicy.wasCreated(null))
        assertFalse(SessionCreationPolicy.wasCreated(""))
        assertFalse(SessionCreationPolicy.wasCreated("   "))
    }

    @Test
    fun `only a newly created session offers the AUQ`() {
        assertTrue(SessionCreationPolicy.requiresAuq("환영합니다", auqScreenAvailable = true))
        assertFalse(SessionCreationPolicy.requiresAuq(null, auqScreenAvailable = true))
    }

    @Test
    fun `before NT-06 ships a new session goes straight to dialogue`() {
        assertFalse(SessionCreationPolicy.requiresAuq("환영합니다", auqScreenAvailable = false))
    }
}
