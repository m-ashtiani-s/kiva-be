package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason

/**
 * The Persian words of the payment's result page (roadmap section 10.7), made here because the domain only gives codes:
 * why an attempt didn't succeed, and the three next steps after it did.
 */
object PaymentTexts {

    /**
     * Why the attempt didn't succeed. A payment the gateway may have taken but we never confirmed says the money comes
     * back by itself; one refused for an order that couldn't take it any more says why ([orderStatus]: expired, or paid
     * by another attempt).
     */
    fun failureReason(reason: PaymentFailureReason, orderStatus: OrderStatus): String = when (reason) {
        PaymentFailureReason.USER_CANCELLED -> "تراکنش توسط کاربر لغو شد"
        PaymentFailureReason.GATEWAY_DECLINED -> "بانک پرداخت رو تأیید نکرد. اگه مبلغی از حسابت کم شده، خودکار برمی‌گرده"
        PaymentFailureReason.ORDER_NOT_PAYABLE ->
            if (orderStatus.isPaid) {
                "این سفارش با یه پرداخت دیگه پرداخت شده بود. اگه مبلغی از حسابت کم شده، خودکار برمی‌گرده"
            } else {
                "مهلت پرداخت این سفارش تموم شده بود. اگه مبلغی از حسابت کم شده، خودکار برمی‌گرده"
            }
        PaymentFailureReason.GATEWAY_UNREACHABLE -> "درگاه پرداخت جواب نداد. چند دقیقه بعد دوباره امتحان کن"
        PaymentFailureReason.GATEWAY_REJECTED -> "درگاه پرداخت درخواست رو قبول نکرد. چند دقیقه بعد دوباره امتحان کن یا درگاه دیگه‌ای رو انتخاب کن"
        PaymentFailureReason.EXPIRED -> "مهلت پرداخت این سفارش تموم شد"
    }

    /**
     * The three steps after a payment (contract example): what happens to the order now, where its photos before
     * shipping go, and where the tracking code shows up.
     */
    fun nextSteps(orderStatus: OrderStatus, messengerName: String, shippingMethodName: String): List<String> = listOf(
        if (orderStatus == OrderStatus.RESERVED) "سفارشت رزرو شد و تا پایان مهلت رزرو منتظر خریدهای بعدیته." else "سفارشت در حال آماده‌سازیه.",
        "عکس و ویدیوی کیفت رو قبل از ارسال توی $messengerName برات می‌فرستیم.",
        "بعد از تحویل به $shippingMethodName، کد رهگیری توی حسابت قرار می‌گیره.",
    )
}
