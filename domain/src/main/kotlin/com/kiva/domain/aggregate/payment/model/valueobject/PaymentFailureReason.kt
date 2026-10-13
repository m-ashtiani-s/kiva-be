package com.kiva.domain.aggregate.payment.model.valueobject

/**
 * Why a payment attempt did not succeed (roadmap section 10.1). Kept with the attempt, and with the order's
 * OrderPaymentFailed when the attempt failed the order. The Persian text the shopper reads is the HTTP layer's.
 */
enum class PaymentFailureReason {
    /** The shopper cancelled on the gateway's page. */
    USER_CANCELLED,

    /** The gateway said the payment didn't go through, or refused to confirm it. */
    GATEWAY_DECLINED,

    /** The order could no longer be paid when the shopper came back: not confirmed, so the gateway returns the money. */
    ORDER_NOT_PAYABLE,

    /** The gateway didn't answer when the attempt started. */
    GATEWAY_UNREACHABLE,

    /** The gateway refused our request (merchant, domain, amount limit): our configuration is wrong. */
    GATEWAY_REJECTED,

    /** The order's time to pay ran out. */
    EXPIRED,
}
