package com.kiva.domain.aggregate.payment.model.valueobject

/**
 * The card a payment was made with, as the gateway shows it: never the whole number («6037-99**-****-1234»). Kept for
 * the result page and support; the gateway's card hash is not kept at all.
 */
@JvmInline
value class CardMask private constructor(val value: String) {

    init {
        require(FORMAT.matches(value) && value.count { it == '*' } >= HIDDEN_DIGITS) { "Invalid CardMask of length ${value.length}" }
    }

    companion object {
        // Digits, stars and dashes only, with the six middle digits hidden as gateways show them (first six and last four
        // left): so a whole, or a barely hidden, card number is never kept by mistake.
        private const val HIDDEN_DIGITS = 6
        private val FORMAT = Regex("[0-9*-]{8,32}")

        /**
         * The card as a gateway's answer shows it (Zarinpal's `card_pan`, «502229******5995»): null when it isn't masked
         * enough, or isn't a card at all, so a whole card number is never kept even if a gateway sends one.
         */
        fun of(raw: String?): CardMask? = raw?.trim()?.takeIf { FORMAT.matches(it) && it.count { c -> c == '*' } >= HIDDEN_DIGITS }?.let(::CardMask)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): CardMask = CardMask(value)
    }
}
