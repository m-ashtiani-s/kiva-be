package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel

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

    /** The messenger's name, as checkout lists it. */
    fun messengerName(channel: MessengerChannel): String = CheckoutTexts.messengers.first { it.channel == channel.name }.name
}
