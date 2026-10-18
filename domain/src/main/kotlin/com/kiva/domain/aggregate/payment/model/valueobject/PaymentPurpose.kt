package com.kiva.domain.aggregate.payment.model.valueobject

/** What an attempt pays (roadmap section 8.2, row 8; the contract's `OrderPaymentInfo.purpose`). */
enum class PaymentPurpose {
    /** The order itself: its whole payable, once. Every attempt before phase 3 is one. */
    ORDER,

    /** What a paid order owes after a change to a dearer color: its `balanceDue` when the attempt started. */
    BALANCE,
}
