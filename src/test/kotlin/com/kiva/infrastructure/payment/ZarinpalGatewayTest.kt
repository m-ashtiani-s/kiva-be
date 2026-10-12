package com.kiva.infrastructure.payment

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.port.GatewayCallback
import com.kiva.application.port.GatewayFailure
import com.kiva.application.port.GatewayInquiry
import com.kiva.application.port.GatewayPaymentRequest
import com.kiva.application.port.GatewayReceipt
import com.kiva.application.port.GatewayRedirect
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Zarinpal adapter against a pretend Zarinpal (Ktor's MockEngine): what it sends, and how it reads every kind of
 * answer, from REST v4's own shapes. No network.
 */
class ZarinpalGatewayTest {

    private val merchantId = "1344b5d4-0048-11e8-94db-005056a205be"
    private val config = PaymentConfig(
        PaymentProvider.ZARINPAL, "https://api.kiva.ir", "https://kiva.ir/checkout/result", ZarinpalConfig(merchantId, sandbox = false),
    )
    private val authority = GatewayAuthority.constructFromPersisted("A00000000000000000000000000217885159")
    private val request = GatewayPaymentRequest(PaymentPublicId.constructFromPersisted("pay_01J9X2K7AB3C"), OrderCode.constructFromPersisted("KV-218340"), Money.constructFromPersisted(1_525_000))
    private val amount = Money.constructFromPersisted(1_525_000)

    /** What the adapter sent: the path and the JSON body of each call. */
    private val sent = Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())

    private fun gateway(
        config: PaymentConfig = this.config,
        timeouts: ZarinpalGateway.Timeouts = ZarinpalGateway.Timeouts.DEFAULT,
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = ZarinpalGateway(
        config,
        MockEngine { call ->
            sent += call.url.toString() to Json.parseToJsonElement((call.body as TextContent).text).jsonObject
            answer(call)
        },
        timeouts,
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.success(data: String) = json("""{"data":$data,"errors":[]}""")

    private fun MockRequestHandleScope.error(code: Int, status: HttpStatusCode = HttpStatusCode.UnprocessableEntity) =
        json("""{"data":[],"errors":{"code":$code,"message":"Zarinpal says $code","validations":[]}}""", status)

    /** Runs [block] and returns every line it logged, of every logger. */
    private fun logged(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.toList()
    }

    // ============ opening a session ============

    @Test
    fun `a session is opened with the amount in rials, and the shopper goes to StartPay with its authority`() = runBlocking {
        val gateway = gateway { success("""{"code":100,"message":"Success","authority":"${authority.value}","fee_type":"Merchant","fee":100}""") }

        val opened = gateway.requestPayment(request)

        assertEquals(
            Result.success(GatewayRedirect(authority, PaymentRedirect("https://payment.zarinpal.com/pg/StartPay/${authority.value}", PaymentRedirect.Method.GET))),
            opened,
        )
        val (url, body) = sent.single()
        assertEquals("https://payment.zarinpal.com/pg/v4/payment/request.json", url)
        assertEquals(
            Json.parseToJsonElement(
                """{"merchant_id":"$merchantId","amount":15250000,"currency":"IRR","description":"سفارش KV-218340 کیوا","callback_url":"https://api.kiva.ir/api/v1/payment-callbacks/zarinpal"}""",
            ),
            body,
        )
    }

    @Test
    fun `the sandbox has its own host, for the call and for StartPay`() = runBlocking {
        val sandbox = PaymentConfig(PaymentProvider.ZARINPAL, "http://localhost:8080", "http://localhost:3100/checkout/result", ZarinpalConfig(merchantId, sandbox = true))
        val gateway = gateway(sandbox) { success("""{"code":100,"message":"Success","authority":"S0000000000000000000000000000000000001"}""") }

        val opened = gateway.requestPayment(request).getOrNull()!!

        assertEquals("https://sandbox.zarinpal.com/pg/StartPay/S0000000000000000000000000000000000001", opened.redirect.url)
        assertEquals("https://sandbox.zarinpal.com/pg/v4/payment/request.json", sent.single().first)
    }

    @Test
    fun `a request Zarinpal refuses for our own configuration is Rejected and logged as ERROR`() {
        listOf(-9, -10, -11, -14, -16, -41).forEach { code ->
            val lines = logged { runBlocking { assertEquals(Result.failure(GatewayFailure.Rejected(code)), gateway { error(code) }.requestPayment(request)) } }
            assertTrue(lines.any { it.level.toString() == "ERROR" && "rejected $code" in it.formattedMessage }, "$code: ${lines.map { it.formattedMessage }}")
        }
    }

    @Test
    fun `no answer in time, a server error, an answer that isn't Zarinpal's or -52 are Unreachable`() = runBlocking {
        val quick = ZarinpalGateway.Timeouts(connect = Duration.ofMillis(100), request = Duration.ofMillis(200))
        val slow = gateway(timeouts = quick) {
            delay(2_000)
            success("""{"code":100,"authority":"${authority.value}"}""")
        }

        assertEquals(Result.failure(GatewayFailure.Unreachable), slow.requestPayment(request))
        assertEquals(Result.failure(GatewayFailure.Unreachable), gateway { json("{}", HttpStatusCode.BadGateway) }.requestPayment(request))
        assertEquals(Result.failure(GatewayFailure.Unreachable), gateway { respond("<html>Bad gateway</html>") }.requestPayment(request))
        assertEquals(Result.failure(GatewayFailure.Unreachable), gateway { error(-52) }.requestPayment(request))
        assertEquals(Result.failure(GatewayFailure.Unreachable), gateway { success("""{"code":100,"authority":"<script>"}""") }.requestPayment(request))
    }

    @Test
    fun `it waits 5 seconds to connect and 15 for the whole call`() {
        assertEquals(ZarinpalGateway.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(15)), ZarinpalGateway.Timeouts.DEFAULT)
    }

    // ============ confirming ============

    @Test
    fun `confirming sends the amount we stored, in rials, and 100 and 101 are both the payment, with the masked card and never its hash`() = runBlocking {
        val now = gateway { success("""{"code":100,"message":"Paid","card_hash":"1EBE3EBEBE35C7EC0F8D6EE4F2F859107A87822CA179BC9528767EA7B5489B69","card_pan":"502229******5995","ref_id":201,"fee_type":"Merchant","fee":0}""") }
        val before = gateway { success("""{"code":101,"message":"Verified","card_hash":"1EBE3E","card_pan":"502229******5995","ref_id":201}""") }

        val confirmed = now.verify(authority, amount)
        val again = before.verify(authority, amount)

        val receipt = GatewayReceipt(GatewayRefId.constructFromPersisted("201"), CardMask.constructFromPersisted("502229******5995"), alreadyConfirmed = false)
        assertEquals(Result.success(receipt), confirmed)
        assertEquals(Result.success(receipt.copy(alreadyConfirmed = true)), again)
        assertFalse("1EBE3E" in confirmed.toString())
        assertEquals(
            Json.parseToJsonElement("""{"merchant_id":"$merchantId","amount":15250000,"authority":"${authority.value}"}"""),
            sent.first().second,
        )
        assertEquals("https://payment.zarinpal.com/pg/v4/payment/verify.json", sent.first().first)
    }

    @Test
    fun `a card shown whole is not kept, the payment is`() = runBlocking {
        val receipt = gateway { success("""{"code":100,"card_pan":"5022291234565995","ref_id":202}""") }.verify(authority, amount).getOrNull()!!

        assertNull(receipt.cardMask)
        assertEquals("202", receipt.refId.value)
    }

    @Test
    fun `another amount paid is AmountMismatch and an ERROR, a payment that failed is Declined`() {
        val lines = logged { runBlocking { assertEquals(Result.failure(GatewayFailure.AmountMismatch), gateway { error(-50) }.verify(authority, amount)) } }
        assertTrue(lines.any { it.level.toString() == "ERROR" && "-50" in it.formattedMessage })

        runBlocking {
            assertEquals(Result.failure(GatewayFailure.Declined(-51)), gateway { error(-51) }.verify(authority, amount))
            assertEquals(Result.failure(GatewayFailure.Declined(-55)), gateway { error(-55) }.verify(authority, amount))
            assertEquals(Result.failure(GatewayFailure.Unreachable), gateway { success("""{"code":100,"card_pan":"502229******5995"}""") }.verify(authority, amount), "no ref_id")
        }
    }

    // ============ asking, and giving back ============

    @Test
    fun `an inquiry tells how the session stands`() = runBlocking {
        GatewayInquiry.entries.forEach { status ->
            assertEquals(Result.success(status), gateway { success("""{"code":100,"message":"Success","status":"${status.name}"}""") }.inquire(authority))
        }
        assertEquals(Result.failure(GatewayFailure.Unreachable), gateway { success("""{"code":100,"status":"LOST"}""") }.inquire(authority))
        assertEquals(Result.failure(GatewayFailure.Declined(-54)), gateway { error(-54) }.inquire(authority))
        assertTrue(sent.all { it.first == "https://payment.zarinpal.com/pg/v4/payment/inquiry.json" })
    }

    @Test
    fun `giving back works within its time, and is Declined after it or for a payment not confirmed`() = runBlocking {
        assertEquals(Result.success(Unit), gateway { success("""{"code":100,"message":"Reversed"}""") }.reverse(authority))
        assertEquals(Result.failure(GatewayFailure.Declined(-63)), gateway { error(-63) }.reverse(authority))
        assertEquals(Result.failure(GatewayFailure.Declined(-61)), gateway { error(-61) }.reverse(authority))
        assertEquals(Result.failure(GatewayFailure.Rejected(-62)), gateway { error(-62) }.reverse(authority), "our address isn't registered: configuration")
        assertEquals("https://payment.zarinpal.com/pg/v4/payment/reverse.json", sent.first().first)
    }

    // ============ the callback, and secrets ============

    @Test
    fun `a callback is Zarinpal's when it has an Authority and Status OK or NOK`() {
        val gateway = gateway { success("{}") }

        assertEquals(GatewayCallback(authority, paid = true), gateway.readCallback(mapOf("Authority" to authority.value, "Status" to "OK")))
        assertEquals(GatewayCallback(authority, paid = false), gateway.readCallback(mapOf("Authority" to authority.value, "Status" to "NOK")))
        assertNull(gateway.readCallback(mapOf("Authority" to authority.value, "Status" to "MAYBE")))
        assertNull(gateway.readCallback(mapOf("RefNum" to "123", "State" to "OK")))
    }

    @Test
    fun `the merchant id is in no log line and not in toString`() {
        val lines = logged {
            runBlocking {
                gateway { error(-10) }.requestPayment(request)
                gateway { error(-50) }.verify(authority, amount)
                gateway { json("{}", HttpStatusCode.InternalServerError) }.inquire(authority)
                gateway { success("""{"code":100,"authority":"${authority.value}"}""") }.requestPayment(request)
            }
        }

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { merchantId in it.formattedMessage }, lines.joinToString { it.formattedMessage })
        assertFalse(merchantId in gateway { success("{}") }.toString())
        assertFalse(merchantId in config.toString())
    }
}
