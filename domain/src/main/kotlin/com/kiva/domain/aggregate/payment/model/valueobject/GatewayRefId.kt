package com.kiva.domain.aggregate.payment.model.valueobject

/**
 * The bank's reference number of a confirmed payment («شماره پیگیری بانکی», Zarinpal's `ref_id`): what the shopper
 * quotes to their bank. Unique per gateway, so one confirmation can never pay two attempts.
 */
@JvmInline
value class GatewayRefId private constructor(val value: String) {

    init {
        require(FORMAT.matches(value)) { "Invalid GatewayRefId '$value'" }
    }

    companion object {
        private val FORMAT = Regex("[A-Za-z0-9-]{1,64}")

        /** A gateway's answer (Zarinpal's `ref_id`, a number): null when it can't be a reference number. */
        fun of(raw: String?): GatewayRefId? = raw?.trim()?.takeIf { FORMAT.matches(it) }?.let(::GatewayRefId)

        /** For values from a gateway's answer or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): GatewayRefId = GatewayRefId(value)
    }
}
