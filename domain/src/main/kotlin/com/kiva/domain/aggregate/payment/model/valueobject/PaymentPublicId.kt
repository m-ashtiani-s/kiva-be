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

        /**
         * An id from a request's path: null when it can't be a payment's, so nothing is looked up for it and the answer
         * is the same PAYMENT_NOT_FOUND as for an id nobody has (the format is nobody's business).
         */
        fun of(raw: String?): PaymentPublicId? = raw?.takeIf { FORMAT.matches(it) }?.let(::PaymentPublicId)

        /** For values from the generator or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): PaymentPublicId = PaymentPublicId(value)
    }
}
