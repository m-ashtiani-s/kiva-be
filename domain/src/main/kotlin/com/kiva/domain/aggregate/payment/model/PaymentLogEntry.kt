package com.kiva.domain.aggregate.payment.model

import java.time.Duration
import java.time.Instant

/**
 * One exchange with a gateway about a payment, kept for good in the payment log (paymentlog_tbl, append-only): what was
 * asked, how it went, the gateway's own code and how long it took. Support reads it when a shopper says «پول کم شد ولی
 * سفارش ثبت نشد». Only codes and numbers: never the gateway's raw answer, the merchant id or a card's hash.
 *
 * @property outcome a short code of how it went (`OK`, `UNREACHABLE`, `REJECTED`, `DECLINED`, `AMOUNT_MISMATCH`, …).
 * @property gatewayCode the gateway's own result code (Zarinpal's 100, 101, -51, …), when it gave one.
 */
data class PaymentLogEntry(
    val kind: Kind,
    val outcome: String,
    val occurredOn: Instant,
    val gatewayCode: Int? = null,
    val httpStatus: Int? = null,
    val duration: Duration? = null,
) {
    enum class Kind {
        /** Opening the session. */
        REQUEST,

        /** The shopper's browser coming back. */
        CALLBACK,

        /** Confirming the payment. */
        VERIFY,

        /** Asking how a session stands, without confirming it. */
        INQUIRY,

        /** Giving the money back right after a payment (an exception, section 10.5). */
        REVERSE,
    }

    init {
        require(OUTCOME.matches(outcome)) { "Invalid payment log outcome '$outcome'" }
        require(httpStatus == null || httpStatus in 100..599) { "Invalid HTTP status $httpStatus" }
        require(duration == null || !duration.isNegative) { "Negative duration $duration" }
    }

    private companion object {
        val OUTCOME = Regex("[A-Z][A-Z0-9_]{0,29}")
    }
}
