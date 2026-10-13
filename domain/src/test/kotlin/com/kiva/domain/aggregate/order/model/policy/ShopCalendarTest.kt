package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.PricingFixtures.date
import com.kiva.domain.aggregate.order.PricingFixtures.fridayJustAfterMidnight
import com.kiva.domain.aggregate.order.PricingFixtures.thursdayNight
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.SATURDAY
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShopCalendarTest {

    private val fridays = setOf(FRIDAY)

    @Nested
    inner class Today {
        @Test
        fun `Thursday 23 59 in Tehran is still Thursday`() {
            assertEquals(date("2026-10-15"), ShopCalendar.dateOf(thursdayNight))
        }

        @Test
        fun `21 00 UTC on Thursday is already Friday in Tehran, which is the day that counts`() {
            val inTehran = ShopCalendar.dateOf(fridayJustAfterMidnight)

            assertEquals(date("2026-10-16"), inTehran)
            assertEquals(FRIDAY, inTehran.dayOfWeek)
        }

        @Test
        fun `Tehran is three and a half hours ahead all year, so the day turns at 20 30 UTC`() {
            assertEquals(date("2026-06-01"), ShopCalendar.dateOf(Instant.parse("2026-06-01T20:29:59Z")))
            assertEquals(date("2026-06-02"), ShopCalendar.dateOf(Instant.parse("2026-06-01T20:30:00Z")))
        }
    }

    @Nested
    inner class WorkingDays {
        @Test
        fun `Thursday plus 3 working days skips Friday and lands on Monday`() {
            assertEquals(date("2026-10-19"), ShopCalendar.plusWorkingDays(date("2026-10-15"), 3, fridays))
        }

        @Test
        fun `0 working days is the day itself`() {
            assertEquals(date("2026-10-15"), ShopCalendar.plusWorkingDays(date("2026-10-15"), 0, fridays))
        }

        @Test
        fun `every non-shipping day of the week is skipped`() {
            // Thursday + 1 with Friday and Saturday off: Sunday.
            assertEquals(date("2026-10-18"), ShopCalendar.plusWorkingDays(date("2026-10-15"), 1, setOf(FRIDAY, SATURDAY)))
        }

        @Test
        fun `with no day off, working days are calendar days`() {
            assertEquals(date("2026-10-18"), ShopCalendar.plusWorkingDays(date("2026-10-15"), 3, emptySet()))
        }

        @Test
        fun `negative working days are a bug`() {
            assertFailsWith<IllegalArgumentException> { ShopCalendar.plusWorkingDays(date("2026-10-15"), -1, fridays) }
        }
    }

    @Nested
    inner class FirstShippingDay {
        @Test
        fun `a shipping day is its own first shipping day`() {
            assertEquals(date("2026-10-15"), ShopCalendar.firstShippingDayFrom(date("2026-10-15"), fridays))
        }

        @Test
        fun `Friday moves on to Saturday`() {
            assertEquals(date("2026-10-17"), ShopCalendar.firstShippingDayFrom(date("2026-10-16"), fridays))
        }

        @Test
        fun `a week without a shipping day is a bug, not an endless loop`() {
            assertFailsWith<IllegalArgumentException> { ShopCalendar.firstShippingDayFrom(date("2026-10-16"), DayOfWeek.entries.toSet()) }
            assertFailsWith<IllegalArgumentException> { ShopCalendar.plusWorkingDays(date("2026-10-16"), 1, DayOfWeek.entries.toSet()) }
        }
    }
}
