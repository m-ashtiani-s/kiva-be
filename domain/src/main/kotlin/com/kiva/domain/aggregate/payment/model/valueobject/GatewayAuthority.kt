package com.kiva.domain.aggregate.payment.model.valueobject

/**
 * The id a gateway gave one payment session (Zarinpal's `authority`, «A0000…217885159»). A callback names its payment
 * by it, never by our own id: so a forged callback can only name a session the gateway really opened for us, and its
 * verify is then done server to server with the amount we stored.
 *
 * Never typed by a shopper: from the gateway's answer, from a callback (checked through [of]) or read back from storage.
 */
@JvmInline
value class GatewayAuthority private constructor(val value: String) {

    init {
        require(FORMAT.matches(value)) { "Invalid GatewayAuthority of length ${value.length}" }
    }

    companion object {
        // matches() checks the whole string, so no ^...$ is needed.
        private val FORMAT = Regex("[A-Za-z0-9_-]{1,100}")

        /** A value from a callback's parameters: null when it can't be an authority, so nothing is looked up for it. */
        fun of(raw: String?): GatewayAuthority? = raw?.trim()?.takeIf { FORMAT.matches(it) }?.let(::GatewayAuthority)

        /** For values from a gateway's answer or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): GatewayAuthority = GatewayAuthority(value)
    }
}
