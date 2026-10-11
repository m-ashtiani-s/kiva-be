package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.model.valueobject.DeliveryWindow
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * When an order leaves the shop and when it arrives. Dates only, in Tehran (see [ShopCalendar]); public holidays are
 * not counted yet, only the shop's non-shipping weekdays (Friday).
 */
object DeliveryWindowPolicy {

    /**
     * With «رزرو ۴ روزه» on, nothing ships before this day: [holdDays] calendar days after [today]
     * («ارسال بعد از پنجشنبه ۲۳ مهر»).
     */
    fun shipAfterDate(today: LocalDate, holdDays: Int): LocalDate {
        require(holdDays >= 0) { "Hold days can't be negative: $holdDays" }
        return today.plusDays(holdDays.toLong())
    }

    /**
     * The day an order leaves the shop: [today], or [shipAfterDate] when it is held for a reservation ([holdDays] not
     * null). A day nothing ships on moves on to the next one that does, so an order placed on Friday ships on Saturday
     * and its delivery is counted from there, a day later than Thursday's.
     */
    fun shipDate(today: LocalDate, holdDays: Int?, nonShippingWeekdays: Set<DayOfWeek>): LocalDate {
        val earliest = holdDays?.let { shipAfterDate(today, it) } ?: today
        return ShopCalendar.firstShippingDayFrom(earliest, nonShippingWeekdays)
    }

    /**
     * The days it arrives: [days] `min` to `max` working days after [shipDate]. Shipped Thursday 2026-10-15 by post
     * (3 to 5 days): Saturday, Sunday, Monday is 2026-10-19; then Tuesday, Wednesday is 2026-10-21.
     */
    fun windowOf(shipDate: LocalDate, days: DeliveryDays, nonShippingWeekdays: Set<DayOfWeek>): DeliveryWindow =
        DeliveryWindow(
            fromDate = ShopCalendar.plusWorkingDays(shipDate, days.min, nonShippingWeekdays),
            toDate = ShopCalendar.plusWorkingDays(shipDate, days.max, nonShippingWeekdays),
        )
}
