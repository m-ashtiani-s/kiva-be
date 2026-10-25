package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.repository.OrderTimelineKind
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.common.http.toPersianNumber

/**
 * The Persian words of an order's card, made here because the domain only gives its status (contract section 4): the
 * status's label and tag color, the four steps of its progress, and what the card says while it waits.
 */
object OrderTexts {

    fun statusLabel(status: OrderStatus): String = when (status) {
        OrderStatus.PENDING_PAYMENT -> "در انتظار پرداخت"
        OrderStatus.PAYMENT_FAILED -> "پرداخت ناموفق"
        OrderStatus.EXPIRED -> "منقضی شد"
        OrderStatus.RESERVED -> "رزرو شده"
        OrderStatus.PROCESSING -> "در حال آماده‌سازی"
        OrderStatus.PHOTO_SENT -> "عکس کیف ارسال شد"
        OrderStatus.CHANGE_REQUESTED -> "درخواست تغییر ثبت شد"
        OrderStatus.SHIPPED -> "در مسیر"
        OrderStatus.DELIVERED -> "تحویل شده"
        OrderStatus.CANCELLED -> "لغو شده"
        OrderStatus.RETURN_REQUESTED -> "درخواست مرجوعی"
        OrderStatus.RETURNED -> "مرجوع شد"
        OrderStatus.REFUNDED -> "وجه برگشت داده شد"
    }

    /** The tag's color (contract Tone). */
    fun statusTone(status: OrderStatus): String = when (status) {
        OrderStatus.PENDING_PAYMENT, OrderStatus.PROCESSING, OrderStatus.CHANGE_REQUESTED, OrderStatus.RETURN_REQUESTED -> "WARN"
        OrderStatus.PAYMENT_FAILED, OrderStatus.EXPIRED, OrderStatus.CANCELLED -> "DANGER"
        OrderStatus.RESERVED -> "CREAM"
        OrderStatus.PHOTO_SENT, OrderStatus.SHIPPED, OrderStatus.RETURNED, OrderStatus.REFUNDED -> "DEFAULT"
        OrderStatus.DELIVERED -> "SUCCESS"
    }

    /** The four steps of the stepper, in order: key, label and icon. */
    val progressSteps: List<Triple<String, String, String>> = listOf(
        Triple("PLACED", "ثبت شد", "box"),
        Triple("PHOTO_SENT", "عکس ارسال شد", "camera"),
        Triple("SHIPPED", "تحویل پست شد", "truck"),
        Triple("DELIVERED", "تحویل شد", "heart"),
    )

    /**
     * The step the order is at, or null before it is paid (or when it left the main road): an unpaid, failed or expired
     * order has no step done yet, and the stepper shows all four to come.
     */
    fun currentStepOf(status: OrderStatus): Int? = when (status) {
        OrderStatus.RESERVED, OrderStatus.PROCESSING -> 0
        OrderStatus.PHOTO_SENT, OrderStatus.CHANGE_REQUESTED -> 1
        OrderStatus.SHIPPED -> 2
        OrderStatus.DELIVERED -> 3
        OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED, OrderStatus.EXPIRED, OrderStatus.CANCELLED,
        OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED, OrderStatus.REFUNDED,
        -> null
    }

    /** «بعد از تحویل به پست معمولی این‌جا نمایش داده می‌شه»: the shipment card before there is a tracking code. */
    fun shipmentWaiting(methodName: String): String = "بعد از تحویل به $methodName این‌جا نمایش داده می‌شه"

    /**
     * One line of the order's timeline, by what happened ([kind]); [status] is what the order was after it (a payment
     * held for a reservation says so). [carrierName] names the courier of a shipment; [amount] is money given back.
     */
    fun timelineLabel(kind: OrderTimelineKind, status: OrderStatus, carrierName: String, amount: Money?): String = when (kind) {
        OrderTimelineKind.PLACED -> "سفارش ثبت شد"
        OrderTimelineKind.PAYMENT_FAILED -> "پرداخت ناموفق"
        OrderTimelineKind.PAID -> if (status == OrderStatus.RESERVED) "پرداخت شد و رزرو شد" else "پرداخت شد"
        OrderTimelineKind.EXPIRED -> "مهلت پرداخت تموم شد"
        OrderTimelineKind.RELEASED_FROM_RESERVATION -> "مهلت رزرو تموم شد؛ در حال آماده‌سازی"
        OrderTimelineKind.PHOTOS_SENT -> "عکس کیفت فرستاده شد"
        OrderTimelineKind.PHOTOS_APPROVED -> "عکس رو تأیید کردی"
        OrderTimelineKind.PHOTOS_AUTO_APPROVED -> "خودکار تأیید شد"
        OrderTimelineKind.SHIPPED -> "تحویل $carrierName شد"
        OrderTimelineKind.SHIPMENT_CORRECTED -> "کد رهگیری اصلاح شد"
        OrderTimelineKind.DELIVERED -> "تحویل شد"
        OrderTimelineKind.CANCELLED -> "لغو شد"
        OrderTimelineKind.REFUNDED -> amount?.let { "${it.toman.toPersianNumber()} تومان برگشت داده شد" } ?: "مبلغ برگشت داده شد"
        // A change request; the entry's note is the customer's reason of a rejection, or what staff did.
        OrderTimelineKind.CHANGE_REQUESTED -> "درخواست تغییر ثبت شد"
        OrderTimelineKind.CHANGE_REJECTED -> "درخواستت رد شد"
        OrderTimelineKind.COLOR_CHANGED -> "رنگ عوض شد"
        OrderTimelineKind.MODEL_CHANGED -> "مدل عوض شد"
        OrderTimelineKind.ITEM_REMOVED -> "یه قلم حذف شد"
        OrderTimelineKind.CHANGE_APPLIED -> "درخواستت انجام شد"
        OrderTimelineKind.BALANCE_PAID -> amount?.let { "مانده‌ی ${it.toman.toPersianNumber()} تومانی پرداخت شد" } ?: "مانده پرداخت شد"
        // A return (phase 7); its money given back also has the REFUNDED entry of its refund, with the amount.
        OrderTimelineKind.RETURN_REQUESTED -> "درخواست مرجوعی ثبت شد"
        OrderTimelineKind.RETURN_RECEIVED -> "کالای مرجوعی به انبار رسید"
        OrderTimelineKind.RETURN_REJECTED -> "مرجوعی رد شد"
        OrderTimelineKind.RETURN_CLOSED -> "مرجوعی بسته شد"
        OrderTimelineKind.RETURN_REFUNDED -> "وجه مرجوعی برگشت داده شد"
    }

    /** The photos before shipping, before there are any (contract example). */
    fun photosWaiting(messengerName: String): String =
        "قبل از ارسال، از کیفت عکس و ویدیو می‌گیریم و توی $messengerName برات می‌فرستیم؛ یه نسخه هم همین‌جا قرار می‌گیره."

    /** «خرید دوباره»'s toast: how many bags went into the cart, in Persian digits, or that none could. */
    fun reordered(addedCount: Int): String =
        if (addedCount > 0) "${addedCount.toLong().toPersianNumber()} کیف به سبد اضافه شد" else "کیف‌های این سفارش الان موجود نیستن"

    /** The messenger's name, as checkout lists it. */
    fun messengerName(channel: MessengerChannel): String = CheckoutTexts.messengers.first { it.channel == channel.name }.name
}
