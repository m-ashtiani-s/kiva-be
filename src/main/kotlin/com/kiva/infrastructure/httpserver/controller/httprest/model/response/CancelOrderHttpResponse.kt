package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.infrastructure.common.http.toPersianNumber
import kotlinx.serialization.Serializable

/**
 * Contract schema `CancelOrderResponse`: the order's card as it is now, the refund the cancellation opened (null when
 * nothing was paid), and what the customer is told.
 */
@Serializable
data class CancelOrderHttpResponse(val order: OrderSummaryHttpResponse, val refund: RefundHttpResponse?, val message: String)

/** The Persian words of a customer's cancellation. */
object CancelTexts {

    /** How soon staff call about the money back, in working hours (Refund.EXPECTED_WITHIN). */
    private const val CALL_WITHIN_HOURS = 72L

    /** What the customer is told: with money to give back, that staff will call them for it. */
    fun cancelled(hasRefund: Boolean): String =
        if (hasRefund) {
            "سفارش لغو شد. برای برگردوندن پولت، همکارهامون تا ${CALL_WITHIN_HOURS.toPersianNumber()} ساعت کاری باهات تماس می‌گیرن."
        } else {
            "سفارش لغو شد."
        }
}
