package com.kiva.domain.aggregate.payment.model.valueobject

/**
 * The public id of one payment attempt (`pay_` and 12 random capital letters and digits): the contract's `paymentId`.
 * Random, so it says nothing about how many payments there are. An order's events name the attempts by it.
 *
 * Here before the Payment aggregate itself (step 10), because an order's events already name its payments.
 */
@JvmInline
value class PaymentPublicId private constructor(val value: String) {

    // Never typed by anyone but checked when it is: made by the generator or read back from storage.
    init {
        require(FORMAT.matches(value)) { "Invalid PaymentPublicId '$value'" }
    }

    companion object {
        // matches() checks the whole string, so no ^...$ is needed.
        private val FORMAT = Regex("pay_[0-9A-Z]{12}")

        /** For values from the generator or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): PaymentPublicId = PaymentPublicId(value)
    }
}
