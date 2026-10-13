package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.CartIssueDto
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.cart.model.CartIssueCode
import com.kiva.domain.aggregate.order.model.valueobject.FreeShippingProgress
import com.kiva.domain.aggregate.order.model.valueobject.ShippingUnavailableReason
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.infrastructure.common.http.toPersianNumber

/**
 * The Persian texts of the cart: its issues, the toast after adding or removing, the free-shipping bar and the
 * shipping options' lines. The domain gives only codes and numbers; the words live here, like ProductTexts.
 */
object CartTexts {

    /** «کراس‌بادی نیلا — زیتونی»: a line is a bag in a color. */
    fun itemName(productName: String?, colorName: String?): String = when {
        productName == null -> "این کالا"
        colorName == null -> productName
        else -> "$productName — $colorName"
    }

    /** «کراس‌بادی نیلا — زیتونی (۱ عدد) به سبد اضافه شد»; null when the line couldn't take any more (the issue says why). */
    fun added(productName: String, colorName: String, quantity: Int): String? =
        if (quantity <= 0) null else "${itemName(productName, colorName)} (${quantity.toLong().toPersianNumber()} عدد) به سبد اضافه شد"

    fun removed(productName: String, colorName: String): String = "${itemName(productName, colorName)} از سبد حذف شد"

    /** What changed since the shopper last looked. [shippingMethodName] is the cart's method now. */
    fun issue(issue: CartIssueDto, shippingMethodName: String): String {
        val item = "«${itemName(issue.productName, issue.colorName)}»"
        return when (issue.code) {
            CartIssueCode.PRICE_INCREASED -> "قیمت $item از آخرین بازدیدت بیشتر شده."
            CartIssueCode.PRICE_DECREASED -> "قیمت $item از آخرین بازدیدت کمتر شده."
            CartIssueCode.OUT_OF_STOCK -> "$item تموم شد. تا وقتی توی سبدته، سفارش ثبت نمی‌شه؛ می‌تونی حذفش کنی."
            CartIssueCode.VARIANT_UNAVAILABLE -> "$item دیگه فروخته نمی‌شه. تا وقتی توی سبدته، سفارش ثبت نمی‌شه؛ می‌تونی حذفش کنی."
            CartIssueCode.QUANTITY_REDUCED ->
                if (issue.currentValue == 0L) {
                    "$item از سبدت برداشته شد، چون سقف خرید این محصول با رنگ‌های دیگه‌ش پر شده."
                } else {
                    "تعداد $item به ${(issue.currentValue ?: 0L).toPersianNumber()} عدد کم شد؛ بیشتر از این نمی‌شه خرید."
                }
            CartIssueCode.SHIPPING_METHOD_CHANGED -> "روش ارسالی که انتخاب کرده بودی دیگه در دسترس نیست؛ «$shippingMethodName» انتخاب شد."
            CartIssueCode.DISCOUNT_REMOVED -> "کد تخفیف ${issue.discountCode?.let { "«$it» " }.orEmpty()}دیگه روی سبدت اعمال نمی‌شه و برداشته شد."
        }
    }

    /** «تحویل ۳ تا ۵ روز کاری», always from the days themselves, so the text never disagrees with them. */
    fun delivery(days: DeliveryDays): String =
        if (days.min == days.max) {
            "تحویل ${days.max.toLong().toPersianNumber()} روز کاری"
        } else {
            "تحویل ${days.min.toLong().toPersianNumber()} تا ${days.max.toLong().toPersianNumber()} روز کاری"
        }

    /** Why an option can't be chosen. [groupMethodName] the method the reservation ships by. */
    fun shippingUnavailable(reason: ShippingUnavailableReason, groupMethodName: String): String = when (reason) {
        ShippingUnavailableReason.CONSOLIDATED_WITH_RESERVATION -> "سفارش رزروی با $groupMethodName ارسال می‌شه"
    }

    /** «فقط ۱٬۵۵۰٬۰۰۰ تومان تا ارسال رایگان با پست معمولی», or that it is free now. */
    fun freeShipping(progress: FreeShippingProgress, methodName: String): String =
        if (progress.eligible) {
            "ارسال با $methodName رایگان شد"
        } else {
            "فقط ${toman(progress.remaining)} تومان تا ارسال رایگان با $methodName"
        }

    private fun toman(amount: Money): String = amount.toman.toPersianNumber()
}
