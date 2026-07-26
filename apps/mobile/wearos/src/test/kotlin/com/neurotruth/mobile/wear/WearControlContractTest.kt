package com.neurotruth.mobile.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearControlContractTest {
    @Test
    fun `start and stop require a request id`() {
        val start = measurementRequest("start", "request-1")
        assertEquals(MeasurementRequest("start", "request-1"), start)
        assertNull(measurementRequest("pause", "request-1"))
        assertNull(measurementRequest("start", ""))
    }

    @Test
    fun `watch alert requires server action and alert id and is deduplicated`() {
        assertTrue(shouldPresentServerAlert("alert-1", "required_intervention", false))
        assertFalse(shouldPresentServerAlert("alert-1", "required_intervention", true))
        assertFalse(shouldPresentServerAlert(null, "required_intervention", false))
        assertFalse(shouldPresentServerAlert("alert-1", null, false))
    }
}
