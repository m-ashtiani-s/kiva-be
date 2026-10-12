package com.kiva.domain.aggregate.payment.model.valueobject

/**
 * Where one payment attempt is (roadmap section 10.1). Finer than what the shopper is told ([outcome]): the steps in
 * between say exactly where an attempt stopped, so a crash or a gateway that didn't answer can be picked up again.
 */
enum class PaymentStatus(val outcome: PaymentOutcome) {
    /** Made, the gateway not asked yet (or a crash before its answer was stored). */
    INITIATED(PaymentOutcome.PENDING),

    /** The gateway opened a session; the shopper is sent to its page. */
    REDIRECTED(PaymentOutcome.PENDING),

    /** The gateway refused to open a session or didn't answer. The order is untouched: the shopper did nothing. */
    START_FAILED(PaymentOutcome.FAILED),

    /** The shopper cancelled on the gateway's page. */
    CANCELLED(PaymentOutcome.CANCELLED),

    /** The shopper came back with OK and the order still waits for its money: the payment is being confirmed. */
    VERIFYING(PaymentOutcome.PENDING),

    /** Confirmed by the gateway. */
    SUCCEEDED(PaymentOutcome.SUCCEEDED),

    /** Refused by the gateway, or not confirmed at all because the order couldn't be paid any more. */
    FAILED(PaymentOutcome.FAILED),

    /** The order's time to pay ran out while this attempt was still open. */
    EXPIRED(PaymentOutcome.EXPIRED),

    /** The money went back (a later phase). */
    REFUNDED(PaymentOutcome.REFUNDED),
    ;

    /** Nothing more happens to the attempt: a callback that comes again changes nothing. */
    val isSettled: Boolean get() = this != INITIATED && this != REDIRECTED && this != VERIFYING
}

/** What the shopper is told about an attempt: the contract's PaymentStatus. */
enum class PaymentOutcome {
    /** Not back from the gateway yet, or being confirmed: the result page asks again. */
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    REFUNDED,
}
