package com.kiva.domain.aggregate.payment.model.valueobject

/**
 * Where the shopper's browser goes to pay (the contract's `PaymentInit.redirect`): a page to open (GET, Zarinpal), or
 * a form to post to the bank (POST, Saman and Mellat) with its [fields]. Kept with the attempt, so asking again for the
 * same payment gives the same page.
 */
data class PaymentRedirect(val url: String, val method: Method, val fields: Map<String, String> = emptyMap()) {

    enum class Method { GET, POST }

    init {
        require(url.length <= MAX_URL_LENGTH && URL.matches(url)) { "Invalid redirect URL of length ${url.length}" }
        require(method == Method.POST || fields.isEmpty()) { "A GET redirect carries no form fields" }
    }

    companion object {
        const val MAX_URL_LENGTH = 500
        private val URL = Regex("https?://\\S+")
    }
}
