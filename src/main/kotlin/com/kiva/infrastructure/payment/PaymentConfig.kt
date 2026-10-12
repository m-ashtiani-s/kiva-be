package com.kiva.infrastructure.payment

import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode

/**
 * How payments are taken (`kiva.payment`, roadmap section 19).
 *
 * @property provider which adapter stands behind the ZARINPAL gateway: the fake one (development and tests) or the
 *   real one (step 18).
 * @property callbackBaseUrl this API's public address (`KIVA_API_PUBLIC_URL`, `https://api.kiva.ir` on the server): a
 *   gateway sends the shopper back to `{callbackBaseUrl}/api/v1/payment-callbacks/...`. Its domain must be the one
 *   registered with Zarinpal.
 * @property resultUrl the frontend's page that shows how the payment went; `?paymentId=...` is added to it. Fixed here,
 *   never taken from a request, so a callback can't send the shopper anywhere else.
 */
class PaymentConfig(
    val provider: PaymentProvider,
    val callbackBaseUrl: String,
    val resultUrl: String,
    val zarinpal: ZarinpalConfig = ZarinpalConfig(merchantId = null, sandbox = true),
) {
    init {
        require(ADDRESS.matches(callbackBaseUrl)) { "kiva.payment.callbackBaseUrl (KIVA_API_PUBLIC_URL) '$callbackBaseUrl' must be http(s) without a path, like https://api.kiva.ir" }
        require(ADDRESS_WITH_PATH.matches(resultUrl)) { "kiva.payment.resultUrl (KIVA_PAYMENT_RESULT_URL) '$resultUrl' must be http(s), without a / at the end" }
        if (provider == PaymentProvider.ZARINPAL) {
            require(zarinpal.merchantId != null) {
                "kiva.payment.provider is zarinpal but KIVA_ZARINPAL_MERCHANT_ID is not set. It is the 36-character id from the Zarinpal panel."
            }
        }
    }

    /** Where [gateway] sends the shopper back: this API's callback route of that gateway. */
    fun callbackUrlOf(gateway: PaymentGatewayCode): String = "$callbackBaseUrl/api/v1/payment-callbacks/${gateway.name.lowercase()}"

    override fun toString(): String = "PaymentConfig(provider=$provider, callbackBaseUrl=$callbackBaseUrl, resultUrl=$resultUrl, zarinpal=$zarinpal)"

    companion object {
        private val ADDRESS = Regex("^https?://[^/\\s]+$")
        private val ADDRESS_WITH_PATH = Regex("^https?://[^/\\s]+(/[^\\s]*[^/\\s])?$")

        /** Local development: the fake gateway, this API on 8080 and kiva-fe on 3100. */
        val DEFAULT = PaymentConfig(PaymentProvider.FAKE, "http://localhost:8080", "http://localhost:3100/checkout/result")
    }
}

/**
 * @property merchantId the shop's id at Zarinpal (`KIVA_ZARINPAL_MERCHANT_ID`), a UUID. A secret: never in git, never in
 *   a log; [toString] hides it.
 * @property sandbox Zarinpal's test host (`sandbox.zarinpal.com`); true in development, false on the server.
 */
class ZarinpalConfig(val merchantId: String?, val sandbox: Boolean) {
    init {
        require(merchantId == null || UUID.matches(merchantId)) { "KIVA_ZARINPAL_MERCHANT_ID must be the 36-character UUID from the Zarinpal panel" }
    }

    override fun toString(): String = "ZarinpalConfig(merchantId=${if (merchantId == null) "none" else "<hidden>"}, sandbox=$sandbox)"

    private companion object {
        val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

enum class PaymentProvider {
    /** A page on this server plays the bank. Development and tests only; it says so loudly on startup. */
    FAKE,

    /** Zarinpal itself (purchase step 18). */
    ZARINPAL,
    ;

    companion object {
        /** From the config value (`fake`, `zarinpal`). An unknown name stops the boot rather than quietly taking no payments. */
        fun of(raw: String): PaymentProvider =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("kiva.payment.provider '$raw' is not known; it can be: ${entries.joinToString { it.name.lowercase() }}")
    }
}
