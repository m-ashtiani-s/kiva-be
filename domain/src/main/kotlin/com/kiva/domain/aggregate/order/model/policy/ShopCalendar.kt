package com.kiva.domain.aggregate.order.model.policy

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The shop's calendar: «امروز», «جمعه» and every delivery date are Tehran's, wherever the server runs. This is the only
 * place that knows the time zone; everything else asks it for dates.
 *
 * It never reads a clock: the use case reads one once and passes the instant in, so one request never sees two
 * different todays, and tests choose the moment.
 */
object ShopCalendar {

    /** Tehran: also when the shop's daily jobs run (the clean-up at 04:00). */
    val ZONE: ZoneId = ZoneId.of("Asia/Tehran")

    /** The date in Tehran at [instant]. 2026-10-15T21:00Z is still Thursday in UTC, but already Friday 00:30 in Tehran. */
    fun dateOf(instant: Instant): LocalDate = LocalDate.ofInstant(instant, ZONE)

    /** [date] itself when something ships that day; otherwise the next day that does (a Friday becomes Saturday). */
    fun firstShippingDayFrom(date: LocalDate, nonShippingWeekdays: Set<DayOfWeek>): LocalDate {
        requireSomeShippingDay(nonShippingWeekdays)
        var day = date
        while (day.dayOfWeek in nonShippingWeekdays) day = day.plusDays(1)
        return day
    }

    /**
     * [workingDays] working days after [date]: the non-shipping days in between are skipped and don't count.
     * Thursday plus 3 is Monday (Saturday, Sunday, Monday); plus 0 is [date] itself.
     */
    fun plusWorkingDays(date: LocalDate, workingDays: Int, nonShippingWeekdays: Set<DayOfWeek>): LocalDate {
        require(workingDays >= 0) { "Working days can't be negative: $workingDays" }
        requireSomeShippingDay(nonShippingWeekdays)
        var day = date
        var left = workingDays
        while (left > 0) {
            day = day.plusDays(1)
            if (day.dayOfWeek !in nonShippingWeekdays) left--
        }
        return day
    }

    // ShopSettings already refuses a week without a shipping day; without that, the loops above would never end.
    private fun requireSomeShippingDay(nonShippingWeekdays: Set<DayOfWeek>) {
        require(nonShippingWeekdays.size < DayOfWeek.entries.size) { "At least one day of the week must be left for shipping" }
    }
}
