package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.dto.PlacedOrder
import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.payment.FakePaymentGateway
import com.kiva.infrastructure.payment.PaymentConfig
import com.kiva.infrastructure.payment.RandomPaymentIds
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
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
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Back from the gateway and the result page, over the real stack on kiva_test: orders placed as POST /orders places them,
 * the pretend gateway as Zarinpal (its bank's buttons pressed by the test), and the callback called as the shopper's
 * browser would. Step 14 of the purchase roadmap is done when these pass.
 *
 * The pretend gateway refuses any call made inside a database transaction, so every confirmation here is also the
 * architecture test of section 10.4.
 */
class WebV1PaymentsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val purchase = PurchaseTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()
    private val resultUrl = PaymentConfig.DEFAULT.resultUrl

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

    /** One order placed and sent to the gateway, as the shopper and the test know it. */
    private class Placed(val userId: UserId, val orderCode: OrderCode, val paymentId: PaymentPublicId, val authority: String)

    /** A customer of [n] puts a Nila olive in their cart and places an order by post, with Telegram for the photos. */
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
        val order: PlacedOrder = runBlocking { purchase.placeOrder.execute(command) }.getOrNull() ?: error("not placed")
        val paymentId = checkNotNull(order.paymentId)
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(userId, paymentId)) }
        return Placed(userId, order.orderCode, paymentId, authorityOf(paymentId))
    }

    /** Another attempt of the same order, its session opened: what a retry (step 15) makes. */
    private fun anotherAttempt(placed: Placed): Placed {
        val first = transaction(purchase.db) { purchase.payments.find(placed.paymentId)!! }
        val attempt = Payment.start(RandomPaymentIds().nextPaymentPublicId(), first.orderId, first.userId, PaymentGatewayCode.ZARINPAL, first.amount, clock.now)
        transaction(purchase.db) { purchase.payments.add(attempt) }
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(first.userId, attempt.publicId)) }
        return Placed(placed.userId, placed.orderCode, attempt.publicId, authorityOf(attempt.publicId))
    }

    private fun authorityOf(paymentId: PaymentPublicId): String = transaction(purchase.db) {
        PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq paymentId.value }.single()[PaymentTbl.authority]!!
    }

    private fun payment(placed: Placed): Payment = transaction(purchase.db) { purchase.payments.find(placed.paymentId)!! }

    private fun orderStatusOf(placed: Placed): OrderStatus? = transaction(purchase.db) { purchase.orderQm.findOfCustomer(placed.userId, placed.orderCode)?.status }

    private fun callbacksLogged(placed: Placed): List<String> = transaction(purchase.db) {
        val row = PaymentTbl.select(PaymentTbl.id).where { PaymentTbl.publicId eq placed.paymentId.value }.single()[PaymentTbl.id].value
        PaymentLogTbl.select(PaymentLogTbl.kind, PaymentLogTbl.outcome).where { PaymentLogTbl.paymentId eq row }.orderBy(PaymentLogTbl.id)
            .map { "${it[PaymentLogTbl.kind]} ${it[PaymentLogTbl.outcome]}" }
    }

    private fun restock(variantId: Long, delta: Int) {
        others.stock(VariantId.constructFromPersisted(variantId)) {
            it.adjust(StockDelta.constructFromPersisted(delta), AdjustmentReason.RESTOCK, null, others.staff, clock.now)
        }
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = purchase.app(block)

    /** The shopper's browser: it doesn't follow the callback's redirect, so the test sees where it goes. */
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { followRedirects = false }

    private suspend fun HttpClient.back(authority: String, status: String = "OK", gateway: String = "zarinpal"): HttpResponse =
        get("$API_V1/payment-callbacks/$gateway?Authority=$authority&Status=$status")

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

    private suspend fun ApplicationTestBuilder.result(bearer: String?, paymentId: String): HttpResponse =
        client.get("$API_V1/payments/$paymentId") { bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun redirectOf(placed: Placed?) = if (placed == null) resultUrl else "$resultUrl?paymentId=${placed.paymentId.value}"

    // ============ step 14 is done when ============

    @Test
    fun `paid - the attempt succeeds, the order is paid, the shopper goes to the result page, and «پرداخت موفق» is sent`() = app {
        val phone = phone(951)
        val bearer = login(phone)
        val placed = placed(951)
        assertTrue(purchase.gateway.pay(placed.authority))
        clock.advance(Duration.ofMinutes(2))

        val back = browser().back(placed.authority)

        assertEquals(HttpStatusCode.Found, back.status)
        assertEquals(redirectOf(placed), back.headers[HttpHeaders.Location])
        val payment = payment(placed)
        assertEquals(PaymentStatus.SUCCEEDED, payment.status)
        assertNotNull(payment.refId)
        assertEquals(OrderStatus.PROCESSING, orderStatusOf(placed))
        assertEquals(listOf(phone to placed.orderCode.value), purchase.auth.sms.paid.map { it.first.value to it.second.value })
        assertEquals(listOf("REQUEST OK", "CALLBACK OK", "VERIFY OK"), callbacksLogged(placed))

        val shown = result(bearer, placed.paymentId.value)
        assertEquals(HttpStatusCode.OK, shown.status, shown.bodyAsText())
        val body = shown.json()
        assertEquals(listOf("SUCCEEDED", "ZARINPAL", payment.refId!!.value, "6037-99**-****-1234"), listOf("status", "gateway", "referenceId", "cardMask").map { body.text(it) })
        assertEquals(1_525_000, body["amount"]!!.jsonPrimitive.long)
        assertEquals(clock.now, Instant.parse(body.text("paidAt")!!))
        assertEquals(JsonNull, body["failureReason"])
        assertFalse(body["canRetry"]!!.jsonPrimitive.boolean)
        val order = body["order"]!!.jsonObject
        assertEquals(listOf(placed.orderCode.value, "PROCESSING", "false"), listOf(order.text("code"), order.text("status"), order.text("reserved")))
        assertEquals(JsonNull, order["consolidatedInto"])
        assertEquals(
            listOf("سفارشت در حال آماده‌سازیه.", "عکس و ویدیوی کیفت رو قبل از ارسال توی تلگرام برات می‌فرستیم.", "بعد از تحویل به پست معمولی، کد رهگیری توی حسابت قرار می‌گیره."),
            body["nextSteps"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `cancelled at the bank - the attempt is CANCELLED, the order PAYMENT_FAILED, its stock still held, and the page offers a retry`() = app {
        val bearer = login(phone(952))
        val stock = purchase.availableOf(nilaOlive)
        val placed = placed(952)
        assertTrue(purchase.gateway.cancel(placed.authority))

        val back = browser().back(placed.authority, status = "NOK")

        assertEquals(redirectOf(placed), back.headers[HttpHeaders.Location])
        assertEquals(PaymentStatus.CANCELLED, payment(placed).status)
        assertEquals(OrderStatus.PAYMENT_FAILED, orderStatusOf(placed))
        assertEquals(stock - 1, purchase.availableOf(nilaOlive))
        assertEquals(0, purchase.gateway.callsOf(FakePaymentGateway.VERIFY))
        val body = result(bearer, placed.paymentId.value).json()
        assertEquals("CANCELLED", body.text("status"))
        assertEquals("تراکنش توسط کاربر لغو شد", body.text("failureReason"))
        assertTrue(body["canRetry"]!!.jsonPrimitive.boolean)
        assertEquals(emptyList(), body["nextSteps"]!!.jsonArray)
        assertEquals(listOf(JsonNull, JsonNull), listOf(body["referenceId"], body["paidAt"]))
    }

    @Test
    fun `a callback that comes again - a refresh, or a POST after the GET - changes nothing and sends the shopper to the same place`() = app {
        val placed = placed(953)
        purchase.gateway.pay(placed.authority)
        val browser = browser()
        browser.back(placed.authority)
        val settled = payment(placed)

        val again = browser.back(placed.authority)
        val posted = browser.submitForm("$API_V1/payment-callbacks/zarinpal", parameters { append("Authority", placed.authority); append("Status", "OK") })
        val nok = browser.back(placed.authority, status = "NOK")

        assertEquals(List(3) { redirectOf(placed) }, listOf(again, posted, nok).map { it.headers[HttpHeaders.Location] })
        assertEquals(settled.state(), payment(placed).state())
        assertEquals(OrderStatus.PROCESSING, orderStatusOf(placed))
        assertEquals(1, purchase.gateway.callsOf(FakePaymentGateway.VERIFY))
        assertEquals(1, purchase.auth.sms.paid.size)
    }

    @Test
    fun `nothing is found for an unknown session, another gateway, or parameters that aren't a gateway's - no payment changes, no paymentId`() = app {
        val placed = placed(954)
        purchase.gateway.pay(placed.authority)
        val browser = browser()

        val answers = listOf(
            browser.back("A00000000000000000000000000000000001"),
            browser.back(placed.authority, gateway = "saman"),
            browser.back(placed.authority, gateway = "mellat"),
            browser.back(placed.authority, gateway = "paypal"),
            browser.back(placed.authority, status = "MAYBE"),
            browser.get("$API_V1/payment-callbacks/zarinpal"),
            browser.back("A0' OR 1=1"),
        )

        assertEquals(List(answers.size) { HttpStatusCode.Found }, answers.map { it.status })
        assertEquals(List(answers.size) { resultUrl }, answers.map { it.headers[HttpHeaders.Location] })
        assertEquals(PaymentStatus.REDIRECTED, payment(placed).status)
        assertEquals(0, purchase.gateway.callsOf(FakePaymentGateway.VERIFY))
    }

    @Test
    fun `OK without paying - the gateway refuses to confirm, the attempt fails and nothing is paid`() = app {
        val bearer = login(phone(955))
        val placed = placed(955)

        val back = browser().back(placed.authority)

        assertEquals(redirectOf(placed), back.headers[HttpHeaders.Location])
        assertEquals(1, purchase.gateway.callsOf(FakePaymentGateway.VERIFY))
        assertEquals(PaymentStatus.FAILED to PaymentFailureReason.GATEWAY_DECLINED, payment(placed).let { it.status to it.failureReason })
        assertEquals(OrderStatus.PAYMENT_FAILED, orderStatusOf(placed))
        assertEquals(emptyList(), purchase.auth.sms.paid)
        assertEquals(listOf("REQUEST OK", "CALLBACK OK", "VERIFY DECLINED"), callbacksLogged(placed))
        val body = result(bearer, placed.paymentId.value).json()
        assertEquals("FAILED", body.text("status"))
        assertEquals("بانک پرداخت رو تأیید نکرد. اگه مبلغی از حسابت کم شده، خودکار برمی‌گرده", body.text("failureReason"))
        assertTrue(body["canRetry"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `paid after the order expired - never confirmed, the attempt fails as ORDER_NOT_PAYABLE, and the page says the money comes back`() = app {
        val placed = placed(956)
        purchase.gateway.pay(placed.authority)
        // The expiry job (step 16) got there first.
        clock.advance(Duration.ofMinutes(16))
        transaction(purchase.db) {
            val order = purchase.orders.get(payment(placed).orderId)!!
            order.expire(UserId.SYSTEM, clock.now).getOrNull()!!
            purchase.orders.save(order)
        }
        val bearer = login(phone(956))

        val back = browser().back(placed.authority)

        assertEquals(redirectOf(placed), back.headers[HttpHeaders.Location])
        assertEquals(0, purchase.gateway.callsOf(FakePaymentGateway.VERIFY))
        assertEquals(PaymentStatus.FAILED to PaymentFailureReason.ORDER_NOT_PAYABLE, payment(placed).let { it.status to it.failureReason })
        assertEquals(OrderStatus.EXPIRED, orderStatusOf(placed))
        val body = result(bearer, placed.paymentId.value).json()
        assertEquals("مهلت پرداخت این سفارش تموم شده بود. اگه مبلغی از حسابت کم شده، خودکار برمی‌گرده", body.text("failureReason"))
        assertFalse(body["canRetry"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `two attempts both paid - only the first is confirmed, the second fails as ORDER_NOT_PAYABLE and is never confirmed`() = app {
        val bearer = login(phone(957))
        val first = placed(957)
        val second = anotherAttempt(first)
        purchase.gateway.pay(first.authority)
        purchase.gateway.pay(second.authority)
        val browser = browser()

        browser.back(first.authority)
        browser.back(second.authority)

        assertEquals(1, purchase.gateway.callsOf(FakePaymentGateway.VERIFY))
        assertEquals(PaymentStatus.SUCCEEDED, payment(first).status)
        assertEquals(PaymentStatus.FAILED to PaymentFailureReason.ORDER_NOT_PAYABLE, payment(second).let { it.status to it.failureReason })
        assertEquals(OrderStatus.PROCESSING, orderStatusOf(first))
        val body = result(bearer, second.paymentId.value).json()
        assertEquals("این سفارش با یه پرداخت دیگه پرداخت شده بود. اگه مبلغی از حسابت کم شده، خودکار برمی‌گرده", body.text("failureReason"))
        assertEquals("PROCESSING", body["order"]!!.jsonObject.text("status"))
    }

    @Test
    fun `two attempts both paid, back at the same moment, 10 times - never confirmed twice`() {
        restock(nilaOlive, 20)
        repeat(10) { round ->
            val first = placed(960 + round)
            val second = anotherAttempt(first)
            purchase.gateway.pay(first.authority)
            purchase.gateway.pay(second.authority)
            val verifies = purchase.gateway.callsOf(FakePaymentGateway.VERIFY)

            val back = atOnce(2) { listOf(first, second)[it].let { p -> purchase.handleCallback.execute(HandleGatewayCallbackUc.Cmd("zarinpal", mapOf("Authority" to p.authority, "Status" to "OK"))) } }

            assertEquals(listOf(first.paymentId, second.paymentId), back.map { it.getOrNull() }, "round $round")
            assertEquals(verifies + 1, purchase.gateway.callsOf(FakePaymentGateway.VERIFY), "round $round")
            val statuses = listOf(payment(first), payment(second)).map { it.status }
            assertEquals(1, statuses.count { it == PaymentStatus.SUCCEEDED }, "round $round: $statuses")
            assertTrue(statuses.all { it in setOf(PaymentStatus.SUCCEEDED, PaymentStatus.REDIRECTED, PaymentStatus.FAILED) }, "round $round: $statuses")
            assertEquals(OrderStatus.PROCESSING, orderStatusOf(first))
        }
    }

    @Test
    fun `the gateway doesn't answer the confirmation - the attempt stays VERIFYING, the shopper still goes to the result page, which says PENDING`() = app {
        val bearer = login(phone(971))
        val placed = placed(971)
        purchase.gateway.pay(placed.authority)
        purchase.gateway.mode = FakePaymentGateway.Mode.UNREACHABLE

        val back = browser().back(placed.authority)

        assertEquals(redirectOf(placed), back.headers[HttpHeaders.Location])
        assertEquals(PaymentStatus.VERIFYING, payment(placed).status)
        assertEquals(OrderStatus.PENDING_PAYMENT, orderStatusOf(placed))
        assertEquals(listOf("REQUEST OK", "CALLBACK OK", "VERIFY UNREACHABLE"), callbacksLogged(placed))
        val body = result(bearer, placed.paymentId.value).json()
        assertEquals("PENDING", body.text("status"))
        assertEquals(JsonNull, body["failureReason"])
        assertFalse(body["canRetry"]!!.jsonPrimitive.boolean, "no retry while it is being confirmed")
        // A refresh while it is being confirmed changes nothing either; the job confirms it later (step 16).
        purchase.gateway.mode = FakePaymentGateway.Mode.NORMAL
        browser().back(placed.authority)
        assertEquals(PaymentStatus.VERIFYING, payment(placed).status)
        assertEquals(1, purchase.gateway.callsOf(FakePaymentGateway.VERIFY))
    }

    @Test
    fun `our confirmation refused by the gateway - the attempt fails as GATEWAY_REJECTED and the order's try with it`() = app {
        val placed = placed(972)
        purchase.gateway.pay(placed.authority)
        purchase.gateway.mode = FakePaymentGateway.Mode.REJECTING

        browser().back(placed.authority)

        assertEquals(PaymentStatus.FAILED to PaymentFailureReason.GATEWAY_REJECTED, payment(placed).let { it.status to it.failureReason })
        assertEquals(OrderStatus.PAYMENT_FAILED, orderStatusOf(placed))
        assertEquals(listOf("REQUEST OK", "CALLBACK OK", "VERIFY REJECTED"), callbacksLogged(placed))
    }

    @Test
    fun `the result page is the customer's own - someone else's, a made-up and a malformed id are 404, and no login is 401`() = app {
        val placed = placed(973)
        val mina = login(phone(974))

        assertEquals(HttpStatusCode.Unauthorized, result(null, placed.paymentId.value).status)
        listOf(placed.paymentId.value, "pay_ZZZZZZZZZZZZ", "abc").forEach { id ->
            val response = result(mina, id)
            assertEquals(HttpStatusCode.NotFound, response.status, id)
            assertEquals("PAYMENT_NOT_FOUND", response.json().text("code"), id)
        }
    }

    @Test
    fun `an SMS that fails doesn't fail the payment`() = app {
        val placed = placed(975)
        purchase.gateway.pay(placed.authority)
        purchase.auth.sms.down = true

        val back = browser().back(placed.authority)

        assertEquals(redirectOf(placed), back.headers[HttpHeaders.Location])
        assertEquals(PaymentStatus.SUCCEEDED, payment(placed).status)
        assertEquals(OrderStatus.PROCESSING, orderStatusOf(placed))
    }

    @Test
    fun `a callback that breaks inside still sends the shopper to the result page`() = app {
        val placed = placed(976)
        purchase.gateway.pay(placed.authority)
        // The order's events gone from under the payment: settling it can only crash.
        val orderId = payment(placed).orderId.value.toString()
        transaction(purchase.db) { EventStoreTbl.deleteWhere { (aggregateName eq "ORDER") and (aggregateId eq orderId) } }

        val back = browser().back(placed.authority)

        assertEquals(HttpStatusCode.Found, back.status)
        assertEquals(resultUrl, back.headers[HttpHeaders.Location])
    }

    // ============ helpers ============

    private fun Payment.state(): List<Any?> = listOf(status, refId, cardMask, failureReason, verifyingSince, settledOn, version)

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
}
