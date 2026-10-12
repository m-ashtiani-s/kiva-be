package com.kiva.application.port

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode

/**
 * One payment gateway (roadmap section 10.2). The adapters live in infrastructure: Zarinpal (step 18), and a fake one
 * for development and tests that walks the same path through the same callback.
 *
 * **Never called inside a transaction.** A slow gateway would hold a database connection, and a full pool stops the
 * whole shop, not only the payments; the Payment's state machine takes the transaction's place (section 10.5). The fake
 * gateway refuses a call made inside one, so such a call fails the build.
 *
 * Every call returns a Result; a gateway's own failure never throws.
 */
interface PaymentGateway {
    val code: PaymentGatewayCode

    /** Opens a payment session for [request]. The amount is in toman; the adapter converts it to what the gateway wants. */
    suspend fun requestPayment(request: GatewayPaymentRequest): Result<GatewayRedirect, GatewayFailure>

    /** The callback's own parameters (Zarinpal: `Authority`, `Status`); null when they aren't this gateway's. */
    fun readCallback(params: Map<String, String>): GatewayCallback?

    /** Confirms a paid session, with the amount *we* stored, never one from the callback. «Already confirmed» is a success. */
    suspend fun verify(authority: GatewayAuthority, amount: Money): Result<GatewayReceipt, GatewayFailure>

    /** How the session stands, without confirming it. */
    suspend fun inquire(authority: GatewayAuthority): Result<GatewayInquiry, GatewayFailure>
}

/** What a session is opened for. The adapter writes the description the gateway shows («سفارش KV-218340 کیوا»). */
data class GatewayPaymentRequest(val paymentId: PaymentPublicId, val orderCode: OrderCode, val amount: Money)

/** A session opened: its [authority], and where the shopper's browser goes. */
data class GatewayRedirect(val authority: GatewayAuthority, val redirect: PaymentRedirect)

/** The shopper back from the gateway: which session, and whether the gateway says they paid (Zarinpal's OK) or not (NOK). */
data class GatewayCallback(val authority: GatewayAuthority, val paid: Boolean)

/**
 * A confirmed payment: the bank's reference number and the card as the gateway shows it.
 *
 * @property alreadyConfirmed the gateway had confirmed it before (Zarinpal's 101): a callback refreshed, or the job.
 */
data class GatewayReceipt(val refId: GatewayRefId, val cardMask: CardMask?, val alreadyConfirmed: Boolean)

/** How a session stands, as the gateway's inquiry tells it. */
enum class GatewayInquiry {
    /** The shopper is still on the bank's page, or left it without paying yet. */
    IN_BANK,

    /** Paid, not confirmed yet. */
    PAID,

    /** Paid and confirmed. */
    VERIFIED,

    FAILED,

    /** Paid and given back. */
    REVERSED,
}

/** Why a gateway call didn't do what was asked. Logged and acted on; never shown to the shopper as such. */
sealed class GatewayFailure(code: String) : ResultFailure(code, FailureKind.BUSINESS_RULE) {
    /** No answer (network, timeout): the outcome is unknown, so the payment stays as it is and is checked again later. */
    data object Unreachable : GatewayFailure("GATEWAY_UNREACHABLE")

    /** The gateway refused our request (merchant, domain, amount limit): our configuration is wrong. Logged as ERROR. */
    data class Rejected(val gatewayCode: Int) : GatewayFailure("GATEWAY_REJECTED")

    /** The payment itself didn't go through. */
    data class Declined(val gatewayCode: Int) : GatewayFailure("GATEWAY_DECLINED")

    /** What was paid is not what we asked for: a bug. Logged as ERROR, never a payment. */
    data object AmountMismatch : GatewayFailure("GATEWAY_AMOUNT_MISMATCH")
}
