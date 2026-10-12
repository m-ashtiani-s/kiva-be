package com.kiva.infrastructure.payment

import com.kiva.application.port.GatewayCallback
import com.kiva.application.port.GatewayFailure
import com.kiva.application.port.GatewayInquiry
import com.kiva.application.port.GatewayPaymentRequest
import com.kiva.application.port.GatewayReceipt
import com.kiva.application.port.GatewayRedirect
import com.kiva.application.port.PaymentGateway
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * Zarinpal (REST v4, roadmap section 10.3), the shop's gateway. Every call is a JSON POST, server to server, with the
 * merchant id and the amount in **rials** (`Money.toRials`; the shop counts in toman). Never called inside a database
 * transaction (the port's rule; the fake gateway enforces it in the tests).
 *
 * Its answers: `{"data": {"code": 100, …}, "errors": []}` on success, `{"data": [], "errors": {"code": -9, …}}`
 * otherwise. Codes are told apart by whose fault they are, which decides how loudly they are logged:
 * - **Unreachable**: no answer in time, a server error, an answer that isn't Zarinpal's, or -52 («try again»). The
 *   outcome is unknown: the payment stays as it is and is asked about again.
 * - **Declined**: the payment itself didn't go through or doesn't exist (-51, -53, -54, -55), or can't be given back
 *   (-60, -61, -63). The shopper's business, not a fault.
 * - **AmountMismatch** (-50): the session was paid another amount than we confirm. A bug: logged as ERROR.
 * - **Rejected**: anything else (-9 validation, -10 merchant or address, -11 merchant not active, -12 too many, -14
 *   the callback's domain isn't the registered one, -15 to -17 the account, -41 over the limit, -62 the address for
 *   reversing, …): our configuration is wrong, and nothing gets paid until someone fixes it. Logged as ERROR.
 *
 * The merchant id is a secret: in no log, nowhere in [toString]. Neither is the raw answer, nor the card's hash.
 */
class ZarinpalGateway(private val config: PaymentConfig, engine: HttpClientEngine, timeouts: Timeouts = Timeouts.DEFAULT) : PaymentGateway {

    private val logger = getLogger()

    override val code: PaymentGatewayCode = PaymentGatewayCode.ZARINPAL

    private val merchantId = checkNotNull(config.zarinpal.merchantId) { "Zarinpal without its merchant id" }
    private val host = if (config.zarinpal.sandbox) SANDBOX_HOST else HOST

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = timeouts.connect.toMillis()
            requestTimeoutMillis = timeouts.request.toMillis()
            socketTimeoutMillis = timeouts.request.toMillis()
        }
    }

    /** How long to wait for Zarinpal: to connect, and for the whole call. */
    data class Timeouts(val connect: Duration, val request: Duration) {
        companion object {
            val DEFAULT = Timeouts(connect = Duration.ofSeconds(5), request = Duration.ofSeconds(15))
        }
    }

    // ============ the gateway ============

    override suspend fun requestPayment(request: GatewayPaymentRequest): Result<GatewayRedirect, GatewayFailure> {
        val body = buildJsonObject {
            put("merchant_id", merchantId)
            put("amount", request.amount.toRials())
            put("currency", "IRR")
            put("description", "سفارش ${request.orderCode.value} کیوا")
            put("callback_url", config.callbackUrlOf(code))
        }
        return call(REQUEST, body) { data ->
            val authority = GatewayAuthority.of(data.text("authority")) ?: return@call malformed(REQUEST, "authority")
            Result.success(GatewayRedirect(authority, PaymentRedirect("$host/pg/StartPay/${authority.value}", PaymentRedirect.Method.GET)))
        }
    }

    /** Zarinpal's own parameters: `Authority`, and `Status` OK or NOK. */
    override fun readCallback(params: Map<String, String>): GatewayCallback? {
        val authority = GatewayAuthority.of(params[AUTHORITY]) ?: return null
        return when (params[STATUS]) {
            OK -> GatewayCallback(authority, paid = true)
            NOK -> GatewayCallback(authority, paid = false)
            else -> null
        }
    }

    /** 100 confirms it now, 101 says it was confirmed before: both are the payment, with its reference number. */
    override suspend fun verify(authority: GatewayAuthority, amount: Money): Result<GatewayReceipt, GatewayFailure> {
        val body = buildJsonObject {
            put("merchant_id", merchantId)
            put("amount", amount.toRials())
            put("authority", authority.value)
        }
        return call(VERIFY, body, success = setOf(SUCCESS, ALREADY_VERIFIED)) { data ->
            val refId = GatewayRefId.of(data.text("ref_id")) ?: return@call malformed(VERIFY, "ref_id")
            // card_pan is masked by Zarinpal («502229******5995»); card_hash is never read.
            Result.success(GatewayReceipt(refId, CardMask.of(data.text("card_pan")), alreadyConfirmed = data.int("code") == ALREADY_VERIFIED))
        }
    }

    override suspend fun inquire(authority: GatewayAuthority): Result<GatewayInquiry, GatewayFailure> {
        val body = buildJsonObject {
            put("merchant_id", merchantId)
            put("authority", authority.value)
        }
        return call(INQUIRY, body) { data ->
            val status = GatewayInquiry.entries.firstOrNull { it.name == data.text("status") } ?: return@call malformed(INQUIRY, "status")
            Result.success(status)
        }
    }

    override suspend fun reverse(authority: GatewayAuthority): Result<Unit, GatewayFailure> {
        val body = buildJsonObject {
            put("merchant_id", merchantId)
            put("authority", authority.value)
        }
        return call(REVERSE, body) { Result.success(Unit) }
    }

    override fun toString(): String = "ZarinpalGateway(host=$host)"

    // ============ inside ============

    /**
     * POSTs [body] to [method] and reads the answer: a code in [success] goes to [onSuccess] with its `data`; any other
     * code is the failure it means; no answer, a server error, or an answer without a code is Unreachable.
     */
    private suspend fun <T> call(
        method: String,
        body: JsonObject,
        success: Set<Int> = setOf(SUCCESS),
        onSuccess: (JsonObject) -> Result<T, GatewayFailure>,
    ): Result<T, GatewayFailure> {
        val text = try {
            val response = client.post("$host/pg/v4/payment/$method.json") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(body.toString())
            }
            if (response.status.value >= SERVER_ERROR) return unreachable(method, "HTTP ${response.status.value}")
            response.bodyAsText()
        } catch (e: HttpRequestTimeoutException) {
            return unreachable(method, "no answer in time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return unreachable(method, e.javaClass.simpleName)
        }

        val answer = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return unreachable(method, "an answer that isn't JSON")
        val data = answer["data"] as? JsonObject
        val errors = answer["errors"] as? JsonObject
        val code = data?.int("code") ?: errors?.int("code") ?: return unreachable(method, "an answer without a code")
        return when {
            data != null && code in success -> onSuccess(data)
            else -> Result.failure(failureOf(method, code, (errors ?: data)?.text("message")))
        }
    }

    private fun failureOf(method: String, code: Int, message: String?): GatewayFailure = when (code) {
        AMOUNT_MISMATCH -> GatewayFailure.AmountMismatch.also {
            logger.error("Zarinpal $method: -50, the session was paid another amount than we confirm")
        }
        TRY_AGAIN -> GatewayFailure.Unreachable.also { logger.warn("Zarinpal $method: -52, try again later") }
        in DECLINED -> GatewayFailure.Declined(code).also { logger.info("Zarinpal $method: declined $code (${loggable(message)})") }
        // Zarinpal's message says which setting is wrong; it holds no personal data, and never the merchant id we sent.
        else -> GatewayFailure.Rejected(code).also { logger.error("Zarinpal $method: rejected $code (${loggable(message)}); the configuration is wrong") }
    }

    private fun <T> unreachable(method: String, why: String): Result<T, GatewayFailure> {
        logger.warn("Zarinpal $method: unreachable, $why")
        return Result.failure(GatewayFailure.Unreachable)
    }

    private fun <T> malformed(method: String, field: String): Result<T, GatewayFailure> = unreachable(method, "a success without a usable $field")

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    /** Zarinpal's message, for the log: short, and one line. */
    private fun loggable(message: String?): String = message.orEmpty().filterNot { it.isISOControl() }.take(MAX_MESSAGE)

    companion object {
        const val HOST = "https://payment.zarinpal.com"
        const val SANDBOX_HOST = "https://sandbox.zarinpal.com"

        private const val REQUEST = "request"
        private const val VERIFY = "verify"
        private const val INQUIRY = "inquiry"
        private const val REVERSE = "reverse"

        private const val AUTHORITY = "Authority"
        private const val STATUS = "Status"
        private const val OK = "OK"
        private const val NOK = "NOK"

        private const val SUCCESS = 100
        private const val ALREADY_VERIFIED = 101
        private const val AMOUNT_MISMATCH = -50
        private const val TRY_AGAIN = -52
        private val DECLINED = setOf(-51, -53, -54, -55, -60, -61, -63)

        private const val SERVER_ERROR = 500
        private const val MAX_MESSAGE = 120
    }
}
