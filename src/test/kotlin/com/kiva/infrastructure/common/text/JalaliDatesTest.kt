package com.kiva.infrastructure.common.text

import com.kiva.infrastructure.common.text.JalaliDates.JalaliDate
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class JalaliDatesTest {

    @Test
    fun `dates known on both calendars - Nowruz in common and leap years, the last day of a leap Esfand, the first of Mehr`() {
        val known = mapOf(
            LocalDate.of(1979, 2, 11) to JalaliDate(1357, 11, 22),
            LocalDate.of(2000, 1, 1) to JalaliDate(1378, 10, 11),
            LocalDate.of(2024, 3, 20) to JalaliDate(1403, 1, 1),
            LocalDate.of(2025, 3, 20) to JalaliDate(1403, 12, 30),
            LocalDate.of(2025, 3, 21) to JalaliDate(1404, 1, 1),
            LocalDate.of(2026, 3, 20) to JalaliDate(1404, 12, 29),
            LocalDate.of(2026, 3, 21) to JalaliDate(1405, 1, 1),
            LocalDate.of(2026, 9, 22) to JalaliDate(1405, 6, 31),
            LocalDate.of(2026, 9, 23) to JalaliDate(1405, 7, 1),
            LocalDate.of(2026, 10, 12) to JalaliDate(1405, 7, 20),
        )

        known.forEach { (gregorian, jalali) -> assertEquals(jalali, JalaliDates.of(gregorian), "$gregorian") }
    }

    @Test
    fun `the words the shop shows - Persian digits, month and weekday names`() {
        assertEquals("۲۰ مهر", JalaliDates.dayAndMonth(LocalDate.of(2026, 10, 12)))
        assertEquals("جمعه ۲۴ مهر", JalaliDates.weekdayDayAndMonth(LocalDate.of(2026, 10, 16)))
        assertEquals("سه‌شنبه ۱ اردیبهشت", JalaliDates.weekdayDayAndMonth(LocalDate.of(2026, 4, 21)))
        assertEquals("۱۴۰۵", JalaliDates.persianDigits(1405))
    }
}
