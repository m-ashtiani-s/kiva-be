package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.InvoiceDto
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.infrastructure.common.http.toPersianNumber
import com.kiva.infrastructure.common.text.JalaliDates
import com.kiva.infrastructure.pdf.InvoiceDocument
import java.time.Instant

/**
 * The Persian words of the purchase receipt (roadmap phase 14, decision 14), and the receipt they make: every text ready
 * to draw, numbers in Persian digits, money in toman, the date of payment on the Jalali calendar in Tehran's time.
 * The renderer only lays out what [documentOf] gives it.
 */
object InvoiceTexts {
    const val TITLE = "رسید خرید"
    const val NOT_A_TAX_INVOICE = "این رسید فاکتور رسمی مالیاتی نیست"
    const val CANCELLED = "لغو شده"
    const val SELLER = "فروشنده"
    const val BUYER = "خریدار"
    const val NUMBER = "شماره"
    const val PAID_ON = "تاریخ پرداخت"
    const val SHIPPING = "روش ارسال"
    const val TRACKING = "کد رهگیری"
    const val POSTAL_CODE = "کد پستی"
    const val FREE = "رایگان"
    const val THANKS = "ممنون که از کیوا خرید کردی"
    val COLUMNS = listOf("ردیف", "کالا", "رنگ", "تعداد", "قیمت واحد", "قیمت قبل از تخفیف", "جمع")

    /** «۲٬۷۹۰٬۰۰۰ تومان». */
    fun toman(amount: Money): String = "${amount.toman.toPersianNumber()} تومان"

    /** «پنجشنبه ۲۳ مهر ۱۴۰۵، ساعت ۱۱:۳۰» in Tehran's time. */
    fun dateTime(at: Instant): String {
        val local = at.atZone(ShopCalendar.ZONE)
        val time = JalaliDates.persianDigits(local.hour).padStart(2, '۰') + ":" + JalaliDates.persianDigits(local.minute).padStart(2, '۰')
        return "${JalaliDates.weekdayDayMonthAndYear(local.toLocalDate())}، ساعت $time"
    }

    private fun digits(text: String): String = text.map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

    fun documentOf(invoice: InvoiceDto): InvoiceDocument {
        val order = invoice.order
        val totals = order.totals
        val address = order.address
        val shipment = order.summary.shipment
        return InvoiceDocument(
            title = TITLE,
            note = NOT_A_TAX_INVOICE,
            banner = CANCELLED.takeIf { invoice.cancelled },
            seller = listOfNotNull(
                invoice.seller.name,
                invoice.seller.phone?.let(::digits),
                invoice.seller.email,
                invoice.seller.website.removePrefix("https://").removePrefix("http://"),
            ),
            facts = listOfNotNull(
                NUMBER to order.summary.code,
                PAID_ON to dateTime(invoice.paidOn),
                SHIPPING to order.summary.shippingMethodName,
                shipment?.let { TRACKING to it.trackingCode },
            ),
            buyer = listOfNotNull(
                address.recipientName,
                address.recipientPhone?.let(::digits),
                AddressTexts.fullText(address.provinceName, address.cityName, address.addressLine, address.plaque, address.unit),
                address.postalCode?.let { "$POSTAL_CODE ${digits(it)}" },
            ),
            columns = COLUMNS,
            rows = order.items.mapIndexed { index, item ->
                listOf(
                    JalaliDates.persianDigits(index + 1),
                    item.name,
                    item.color.name,
                    JalaliDates.persianDigits(item.quantity),
                    toman(item.unitPrice),
                    item.unitCompareAtPrice?.let(::toman) ?: "—",
                    toman(item.lineTotal),
                )
            },
            totals = listOfNotNull(
                "قیمت کالاها" to toman(totals.itemsCompareAtTotal),
                ("تخفیف محصولات" to toman(totals.productDiscount)).takeIf { totals.productDiscount > Money.ZERO },
                "جمع" to toman(totals.subtotal),
                totals.discountCode?.let { "کد تخفیف ${it.value}" to toman(totals.codeDiscount) },
                "هزینه‌ی ارسال" to if (totals.shippingIsFree) FREE else toman(totals.shippingCost),
                ("مانده‌ی پرداخت‌نشده" to toman(totals.balanceDue)).takeIf { totals.balanceDue > Money.ZERO },
                ("برگشت داده شده" to toman(totals.refunded)).takeIf { totals.refunded > Money.ZERO },
            ),
            payable = "پرداخت‌شده" to toman(totals.payable),
            footer = THANKS,
        )
    }
}
