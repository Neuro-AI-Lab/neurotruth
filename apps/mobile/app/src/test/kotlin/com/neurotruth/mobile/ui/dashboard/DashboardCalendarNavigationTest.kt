package com.neurotruth.mobile.ui.dashboard

import com.neurotruth.mobile.data.DashboardRepository
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardCalendarNavigationTest {

    private val today = LocalDate.of(2026, 7, 29) // Wednesday

    @Test
    fun `day navigation stops at today and rejects tomorrow`() {
        val view = DashboardRepository.CALENDAR_VIEW_DAY
        assertEquals(today, nextCalendarAnchor(view, today.minusDays(1), today))
        assertNull(nextCalendarAnchor(view, today, today))
        assertFalse(isCalendarAnchorSelectable(today.plusDays(1), today))
        assertTrue(isCalendarAnchorSelectable(today, today))
    }

    @Test
    fun `week comparison is Monday based through Sunday`() {
        val view = DashboardRepository.CALENDAR_VIEW_WEEK
        val currentMonday = LocalDate.of(2026, 7, 27)
        val currentSunday = LocalDate.of(2026, 8, 2)
        assertEquals(currentMonday, calendarPeriodStart(view, currentSunday))
        assertNull(nextCalendarAnchor(view, currentMonday, today))
        assertNull(nextCalendarAnchor(view, currentSunday, today))
        assertEquals(
            currentMonday,
            calendarPeriodStart(
                view,
                nextCalendarAnchor(view, currentMonday.minusWeeks(1), today)!!,
            ),
        )
        assertNull(nextCalendarAnchor(view, currentMonday.plusWeeks(1), today))
    }

    @Test
    fun `previous Sunday advances into current week without returning a future Sunday`() {
        val anchor = LocalDate.of(2026, 7, 26)
        val next = nextCalendarAnchor(DashboardRepository.CALENDAR_VIEW_WEEK, anchor, today)
        assertEquals(today, next)
        assertTrue(isCalendarAnchorSelectable(next!!, today))
    }

    @Test
    fun `week navigation clamps across a year boundary`() {
        val newYearToday = LocalDate.of(2027, 1, 1)
        val previousSunday = LocalDate.of(2026, 12, 27)
        val next = nextCalendarAnchor(
            DashboardRepository.CALENDAR_VIEW_WEEK,
            previousSunday,
            newYearToday,
        )
        assertEquals(newYearToday, next)
        assertEquals(
            LocalDate.of(2026, 12, 28),
            calendarPeriodStart(DashboardRepository.CALENDAR_VIEW_WEEK, next!!),
        )
    }

    @Test
    fun `month navigation compares year and month rather than day`() {
        val view = DashboardRepository.CALENDAR_VIEW_MONTH
        assertEquals(
            LocalDate.of(2026, 7, 15),
            nextCalendarAnchor(view, LocalDate.of(2026, 6, 15), today),
        )
        assertNull(nextCalendarAnchor(view, LocalDate.of(2026, 7, 1), today))
        assertNull(nextCalendarAnchor(view, LocalDate.of(2026, 7, 31), today))
        assertNull(nextCalendarAnchor(view, LocalDate.of(2026, 8, 1), today))
    }

    @Test
    fun `late prior month advances into current month without returning a future day`() {
        val next = nextCalendarAnchor(
            DashboardRepository.CALENDAR_VIEW_MONTH,
            LocalDate.of(2026, 6, 30),
            today,
        )
        assertEquals(today, next)
        assertTrue(isCalendarAnchorSelectable(next!!, today))
    }

    @Test
    fun `month navigation clamps from December into January`() {
        val januaryToday = LocalDate.of(2027, 1, 15)
        val next = nextCalendarAnchor(
            DashboardRepository.CALENDAR_VIEW_MONTH,
            LocalDate.of(2026, 12, 31),
            januaryToday,
        )
        assertEquals(januaryToday, next)
        assertEquals(
            LocalDate.of(2027, 1, 1),
            calendarPeriodStart(DashboardRepository.CALENDAR_VIEW_MONTH, next!!),
        )
    }

    @Test
    fun `direct raw future anchor is never eligible regardless of selected period`() {
        assertFalse(isCalendarAnchorSelectable(today.plusDays(1), today))
        assertFalse(isCalendarAnchorSelectable(today.plusDays(2), today))
        assertTrue(isCalendarAnchorSelectable(today.minusYears(1), today))
    }
}
