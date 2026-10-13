package com.kiva.application.dto

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentOutcome
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Instant

/**
 * How one attempt to pay went, for the result page (the contract's PaymentResult), without the Persian words: why it
 * failed and the next steps are the HTTP layer's.
 *
 * @property outcome what the shopper is told: PENDING while the shopper isn't back or the payment is being confirmed.
 * @property refId the bank's reference number, and [cardMask] the card, once it succeeded.
 * @property paidOn when it succeeded.
 * @property failureReason why it didn't succeed; null while it may still, and once it did.
 * @property canRetry the page offers «تلاش دوباره» (PaymentPolicy.canRetry).
 */
data class PaymentResultDto(
    val paymentId: String,
    val outcome: PaymentOutcome,
    val amount: Money,
    val gateway: PaymentGatewayCode,
    val refId: String?,
    val cardMask: String?,
    val paidOn: Instant?,
    val failureReason: PaymentFailureReason?,
    val canRetry: Boolean,
    val order: Order,
) {
    /**
     * The attempt's order as it stands now (perhaps paid by another attempt).
     *
     * @property shippingMethodName the method's name as the shop's settings call it now, for the next steps.
     * @property messengerChannel where the photos before shipping go, for the next steps.
     * @property consolidatedInto the order that started the reservation this one ships with, when it isn't this one.
     */
    data class Order(val code: String, val status: OrderStatus, val shippingMethodName: String, val messengerChannel: MessengerChannel, val consolidatedInto: String? = null)
}
