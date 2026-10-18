package com.kiva.infrastructure.common.text

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Persian (Jalali, solar hijri) dates for the texts the shop shows: «پنجشنبه ۲۳ مهر». No library: the conversion is short,
 * and its tests pin it to dates known on both calendars. Only the HTTP layer uses it; the domain counts in Tehran's
 * Gregorian dates (ShopCalendar).
 */
object JalaliDates {

    /** A Jalali date: [month] 1 (فروردین) to 12 (اسفند). */
    data class JalaliDate(val year: Int, val month: Int, val day: Int)

    private val MONTHS = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")

    private val WEEKDAYS = mapOf(
        DayOfWeek.SATURDAY to "شنبه",
        DayOfWeek.SUNDAY to "یکشنبه",
        DayOfWeek.MONDAY to "دوشنبه",
        DayOfWeek.TUESDAY to "سه‌شنبه",
        DayOfWeek.WEDNESDAY to "چهارشنبه",
        DayOfWeek.THURSDAY to "پنجشنبه",
        DayOfWeek.FRIDAY to "جمعه",
    )

    /** [date] on the Jalali calendar (the 33-year arithmetic every Iranian calendar uses for these centuries). */
    fun of(date: LocalDate): JalaliDate {
        val gy = date.year
        val gm = date.monthValue
        val gd = date.dayOfMonth
        val daysBeforeMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + daysBeforeMonth[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val (jm, jd) = if (days < 186) 1 + days / 31 to 1 + days % 31 else 7 + (days - 186) / 30 to 1 + (days - 186) % 30
        return JalaliDate(jy, jm, jd)
    }

    /** «۲۳ مهر». */
    fun dayAndMonth(date: LocalDate): String = of(date).let { "${persianDigits(it.day)} ${MONTHS[it.month - 1]}" }

    /** «پنجشنبه ۲۳ مهر». */
    fun weekdayDayAndMonth(date: LocalDate): String = "${WEEKDAYS.getValue(date.dayOfWeek)} ${dayAndMonth(date)}"

    /** «یکشنبه ۱۳ مهر ۱۴۰۵». */
    fun weekdayDayMonthAndYear(date: LocalDate): String = "${weekdayDayAndMonth(date)} ${persianDigits(of(date).year)}"

    /** «جمعه». */
    fun weekday(date: LocalDate): String = WEEKDAYS.getValue(date.dayOfWeek)

    /** A number in Persian digits («۴»). */
    fun persianDigits(n: Int): String = n.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")
}
