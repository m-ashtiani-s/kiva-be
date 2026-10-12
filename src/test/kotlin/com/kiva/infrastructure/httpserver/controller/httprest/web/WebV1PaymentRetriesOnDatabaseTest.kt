package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentAttemptUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.IDEMPOTENCY_KEY_HEADER
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.payment.FakePaymentGateway
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Paying again (`POST /payments/{paymentId}/retry` and `POST /me/orders/{orderCode}/payments`) over the real stack on
 * kiva_test, with the pretend gateway as Zarinpal. Step 15 of the purchase roadmap is done when these pass.
 */
class WebV1PaymentRetriesOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val purchase = PurchaseTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        purchase.deleteWhatWasStored()
        seeded.deleteSeeded()
        others.deleteAll()
    }

    private val nilaOlive by lazy { purchase.variantOf("nila-crossbody-bag", "olive") }

    /** An order of customer [n], placed and sent to the gateway, and how the order and its first attempt are known. */
    private class Placed(val userId: UserId, val orderCode: OrderCode, val paymentId: String, val authority: String, val holdUntil: Instant)

    private fun placed(n: Int): Placed {
        val userId = purchase.customer(phone(n))
        runBlocking { purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(userId), nilaOlive, 1)) }.getOrNull() ?: error("not added")
        val key = UUID.randomUUID().toString()
        val command = PlaceOrderUc.Cmd(
            userId = userId, idempotencyKey = key, requestHash = RequestHash.of(key), addressId = null,
            newAddress = AddressFields(null, 8, 394, "خیابان ولیعصر، کوچه نسترن", null, null, "1965843117", "سارا محمدی", "09123456789", true, null, null),
            newAddressAsDefault = null, saveNewAddress = false, messenger = PlaceOrderUc.MessengerFields("TELEGRAM", "09121112233", null, null, saveAsDefault = false),
            shippingMethod = "POST", reserve = null, paymentGateway = "ZARINPAL", customerNote = null, gift = null, expectedPayable = null, acceptTerms = true,
        )
        val order = runBlocking { purchase.placeOrder.execute(command) }.getOrNull() ?: error("not placed")
        val paymentId = checkNotNull(order.paymentId)
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(userId, paymentId)) }
        val holdUntil = transaction(purchase.db) { purchase.orderQm.findOfCustomer(userId, order.orderCode)!!.holdUntil }
        return Placed(userId, order.orderCode, paymentId.value, authorityOf(paymentId.value), holdUntil)
    }

    private fun authorityOf(paymentId: String): String = transaction(purchase.db) {
        PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.authority]!!
    }

    private fun statusOf(paymentId: String): PaymentStatus = transaction(purchase.db) {
        PaymentTbl.select(PaymentTbl.status).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.status]
    }

    private fun failureOf(paymentId: String): PaymentFailureReason? = transaction(purchase.db) {
        PaymentTbl.select(PaymentTbl.failureReason).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.failureReason]
    }

    private fun attemptsOf(placed: Placed): Long = transaction(purchase.db) {
        val orderId = purchase.payments.find(PaymentPublicId.constructFromPersisted(placed.paymentId))!!.orderId.value
        PaymentTbl.selectAll().where { PaymentTbl.orderId eq orderId }.count()
    }

    private fun orderStatusOf(placed: Placed): OrderStatus? = transaction(purchase.db) { purchase.orderQm.findOfCustomer(placed.userId, placed.orderCode)?.status }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = purchase.app(block)

    private suspend fun ApplicationTestBuilder.login(phone: String): String {
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val response = client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"${purchase.auth.lastCodeSentTo(phone).value}"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    /** `POST {path}` as the frontend sends it; [body] null sends none at all. */
    private suspend fun ApplicationTestBuilder.payAgain(bearer: String, path: String, body: String? = "{}", key: String? = UUID.randomUUID().toString()): HttpResponse =
        client.post("$API_V1$path") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            key?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }

    private suspend fun ApplicationTestBuilder.retry(bearer: String, placed: Placed, body: String? = "{}", key: String? = UUID.randomUUID().toString()) =
        payAgain(bearer, "/payments/${placed.paymentId}/retry", body, key)

    private suspend fun ApplicationTestBuilder.back(authority: String, status: String = "OK") =
        createClient { followRedirects = false }.get("$API_V1/payment-callbacks/zarinpal?Authority=$authority&Status=$status")

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String? = json()["code"]?.jsonPrimitive?.content

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    // ============ step 15 is done when ============

    @Test
    fun `a retry after cancelling - 201 with a new attempt, its session opened, and the order's own time to pay`() = app {
        val placed = placed(1101)
        val bearer = login(phone(1101))
        purchase.gateway.cancel(placed.authority)
        back(placed.authority, status = "NOK")
        clock.advance(Duration.ofMinutes(3))

        val response = retry(bearer, placed)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val init = response.json()
        val paymentId = init.text("paymentId")!!
        assertNotEquals(placed.paymentId, paymentId)
        assertEquals(listOf("ZARINPAL", "GET"), listOf(init.text("gateway"), init["redirect"]!!.jsonObject.text("method")))
        assertEquals(1_525_000, init["amount"]!!.jsonPrimitive.long)
        assertEquals(placed.holdUntil, Instant.parse(init.text("expiresAt")!!), "never moved")
        assertEquals(PaymentStatus.REDIRECTED, statusOf(paymentId))
        assertEquals(PaymentStatus.CANCELLED, statusOf(placed.paymentId), "the attempt of before stays as it is")

        // And it pays: the order is paid by the new attempt.
        purchase.gateway.pay(authorityOf(paymentId))
        back(authorityOf(paymentId))
        assertEquals(PaymentStatus.SUCCEEDED, statusOf(paymentId))
        assertEquals(OrderStatus.PROCESSING, orderStatusOf(placed))
    }

    @Test
    fun `no retry for a paid order, nor after the order's time to pay`() = app {
        val paid = placed(1102)
        purchase.gateway.pay(paid.authority)
        back(paid.authority)
        val late = placed(1103)

        assertEquals("ORDER_ALREADY_PAID", retry(login(phone(1102)), paid).also { assertEquals(HttpStatusCode.UnprocessableEntity, it.status) }.code())
        clock.advance(Duration.ofMinutes(15))
        assertEquals("PAYMENT_EXPIRED", retry(login(phone(1103)), late).also { assertEquals(HttpStatusCode.UnprocessableEntity, it.status) }.code())
        assertEquals(1, attemptsOf(late))
    }

    @Test
    fun `a gateway that can't take the payment is 422 PAYMENT_GATEWAY_UNAVAILABLE, and one that isn't a gateway is 400`() = app {
        val placed = placed(1104)
        val bearer = login(phone(1104))

        val saman = retry(bearer, placed, body = """{"gateway":"SAMAN"}""")
        val bitcoin = retry(bearer, placed, body = """{"gateway":"BITCOIN"}""")

        assertEquals(HttpStatusCode.UnprocessableEntity, saman.status)
        assertEquals("PAYMENT_GATEWAY_UNAVAILABLE", saman.code())
        assertEquals(JsonNull, saman.json()["meta"] ?: JsonNull, "no attempt was made")
        assertEquals(HttpStatusCode.BadRequest, bitcoin.status)
        assertEquals(listOf("gateway PAYMENT_GATEWAY_INVALID"), bitcoin.json()["errors"]!!.jsonArray.map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" })
        assertEquals(1, attemptsOf(placed))
    }

    @Test
    fun `the gateway doesn't answer the retry - 422 with the order and the new attempt, to go on from`() = app {
        val placed = placed(1105)
        val bearer = login(phone(1105))
        purchase.gateway.mode = FakePaymentGateway.Mode.UNREACHABLE

        val response = retry(bearer, placed)

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("PAYMENT_GATEWAY_UNAVAILABLE", response.code())
        val meta = response.json()["meta"]!!.jsonObject
        assertEquals(placed.orderCode.value, meta.text("orderCode"))
        assertEquals(PaymentStatus.START_FAILED, statusOf(meta.text("paymentId")!!))
    }

    @Test
    fun `paying from the order's page by its code works the same, and only for the customer's own order`() = app {
        val placed = placed(1106)
        val bearer = login(phone(1106))
        val mina = login(phone(1107))
        val path = "/me/orders/${placed.orderCode.value}/payments"

        val paid = payAgain(bearer, path, body = null)

        assertEquals(HttpStatusCode.Created, paid.status, paid.bodyAsText())
        assertEquals("ZARINPAL", paid.json().text("gateway"), "no body keeps the gateway of before")
        assertEquals(2, attemptsOf(placed))
        listOf(path, "/me/orders/KV-000000/payments", "/me/orders/abc/payments").forEach {
            val response = payAgain(mina, it)
            assertEquals(HttpStatusCode.NotFound, response.status, it)
            assertEquals("ORDER_NOT_FOUND", response.code(), it)
        }
        listOf("/payments/${placed.paymentId}/retry", "/payments/pay_ZZZZZZZZZZZZ/retry", "/payments/abc/retry").forEach {
            val response = payAgain(mina, it)
            assertEquals(HttpStatusCode.NotFound, response.status, it)
            assertEquals("PAYMENT_NOT_FOUND", response.code(), it)
        }
        assertEquals(2, attemptsOf(placed))
    }

    @Test
    fun `the Idempotency-Key works as for placing an order - the same request gives the same attempt, another body 409, none 400`() = app {
        val placed = placed(1108)
        val bearer = login(phone(1108))
        val key = UUID.randomUUID().toString()
        val sessions = purchase.gateway.callsOf(FakePaymentGateway.REQUEST)

        val first = retry(bearer, placed, key = key)
        val again = retry(bearer, placed, body = """{"gateway":null}""", key = key)

        assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Created), listOf(first.status, again.status))
        assertEquals(first.json().text("paymentId"), again.json().text("paymentId"))
        assertEquals(sessions + 1, purchase.gateway.callsOf(FakePaymentGateway.REQUEST), "a repeat never opens another session")

        val other = retry(bearer, placed, body = """{"gateway":"SAMAN"}""", key = key)
        val sameKeyByCode = payAgain(bearer, "/me/orders/${placed.orderCode.value}/payments", key = key)
        val none = retry(bearer, placed, key = null)

        assertEquals(HttpStatusCode.Conflict, other.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", other.code())
        assertEquals(HttpStatusCode.Created, sameKeyByCode.status, "another kind of request: another key")
        assertEquals(HttpStatusCode.BadRequest, none.status)
        assertEquals(3, attemptsOf(placed))
    }

    @Test
    fun `the eleventh attempt is 429 RATE_LIMITED, until the order's time to pay ends`() = app {
        val placed = placed(1109)
        val bearer = login(phone(1109))
        repeat(9) { assertEquals(HttpStatusCode.Created, retry(bearer, placed).status) }
        clock.advance(Duration.ofMinutes(4))

        val eleventh = retry(bearer, placed)

        assertEquals(HttpStatusCode.TooManyRequests, eleventh.status)
        assertEquals("RATE_LIMITED", eleventh.code())
        val wait = Duration.between(clock.now, placed.holdUntil).seconds
        assertEquals(wait, eleventh.json()["meta"]!!.jsonObject["retryAfterSeconds"]!!.jsonPrimitive.long)
        assertEquals(wait.toString(), eleventh.headers[HttpHeaders.RetryAfter])
        assertEquals(10, attemptsOf(placed))
    }

    @Test
    fun `15 retries of one order at once, 10 times - exactly 10 attempts, the rest 429`() {
        others.stock(VariantId.constructFromPersisted(nilaOlive)) {
            it.adjust(StockDelta.constructFromPersisted(20), AdjustmentReason.RESTOCK, null, others.staff, clock.now)
        }
        repeat(10) { round ->
            val placed = placed(1120 + round)

            val results = atOnce(15) {
                val key = UUID.randomUUID().toString()
                purchase.startPaymentAttempt.execute(
                    StartPaymentAttemptUc.Cmd(placed.userId, StartPaymentAttemptUc.Target.Attempt(placed.paymentId), key, RequestHash.of(key), gateway = null),
                )
            }

            assertEquals(9, results.count { it is Result.Success }, "round $round: ${results.mapNotNull { it.failureOrNull()?.code }}")
            assertEquals(List(6) { "RATE_LIMITED" }, results.mapNotNull { it.failureOrNull()?.code }, "round $round")
            assertEquals(10, attemptsOf(placed), "round $round")
        }
    }

    /** Runs [work] on [n] threads released together, so the database really sees the calls at the same moment. */
    private fun <T> atOnce(n: Int, work: suspend (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        return try {
            val calls = List(n) { index -> pool.submit<T> { start.await(); runBlocking { work(index) } } }
            start.countDown()
            calls.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `an old attempt paid after a new one was made still pays the order, and the new one then isn't confirmed`() = app {
        val placed = placed(1110)
        val bearer = login(phone(1110))
        val newer = retry(bearer, placed).json().text("paymentId")!!
        purchase.gateway.pay(placed.authority)
        purchase.gateway.pay(authorityOf(newer))

        back(placed.authority)
        back(authorityOf(newer))

        assertEquals(PaymentStatus.SUCCEEDED, statusOf(placed.paymentId))
        assertEquals(OrderStatus.PROCESSING, orderStatusOf(placed))
        assertEquals(PaymentStatus.FAILED to PaymentFailureReason.ORDER_NOT_PAYABLE, statusOf(newer) to failureOf(newer))
        assertEquals(1, purchase.gateway.callsOf(FakePaymentGateway.VERIFY))
        assertTrue(purchase.auth.sms.paid.size == 1)
    }
}
