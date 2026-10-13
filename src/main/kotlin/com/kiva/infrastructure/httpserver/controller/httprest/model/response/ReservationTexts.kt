package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.infrastructure.common.text.JalaliDates
import java.time.Instant
import java.time.LocalDate

/**
 * The Persian words of «رزرو ۴ روزه» (roadmap section 13), made here because the domain only gives dates and codes: the
 * three cards of the cart's switch, the message of an order that joins a reservation, and the countdown's sentence.
 * Dates are Tehran's, on the Jalali calendar.
 */
object ReservationTexts {

    /**
     * The switch's three cards, for an order placed [today] and shipped from [shipAfterDate]:
     * «امروز، ۱۹ مهر · پرداخت کامل و ثبت سفارش», «تا پنجشنبه ۲۳ مهر · …», «بعد از ۴ روز · …».
     */
    fun timeline(today: LocalDate, shipAfterDate: LocalDate, holdDays: Int): List<ReservationTimelineCardHttpResponse> = listOf(
        ReservationTimelineCardHttpResponse("امروز، ${JalaliDates.dayAndMonth(today)}", "پرداخت کامل و ثبت سفارش"),
        ReservationTimelineCardHttpResponse("تا ${JalaliDates.weekdayDayAndMonth(shipAfterDate)}", "هر محصول دیگه‌ای خواستی به همین سفارش اضافه کن"),
        ReservationTimelineCardHttpResponse("بعد از ${JalaliDates.persianDigits(holdDays)} روز", "همه با هم و با یک هزینه ارسال فرستاده می‌شن"),
    )

    /** «این خرید به سفارش رزروی KV-218340 اضافه می‌شه و هزینه ارسال نداره.» */
    fun joins(reservedOrderCode: String): String = "این خرید به سفارش رزروی $reservedOrderCode اضافه می‌شه و هزینه ارسال نداره."

    /** «تا سه‌شنبه ۱۵ مهر هر خریدی کنی، با همین سفارش و بدون هزینه ارسال جدید فرستاده می‌شه.» */
    fun holdsUntil(expiresAt: Instant): String =
        "تا ${JalaliDates.weekdayDayAndMonth(ShopCalendar.dateOf(expiresAt))} هر خریدی کنی، با همین سفارش و بدون هزینه ارسال جدید فرستاده می‌شه."
}
