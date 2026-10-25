package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.payment.usecase.command.ReconcilePaymentUc
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.order.model.valueobject.ChangeRequest
import com.kiva.domain.aggregate.order.model.valueobject.ChangeTarget
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPurpose
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import org.jetbrains.exposed.v1.core.eq
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
import kotlin.test.assertTrue

/**
 * Paying what a change left owed (phase 3 step 3.2) over the real stack on kiva_test: `POST /me/orders/{orderCode}/payments`
 * on a paid order that owes something, the pretend gateway, its callback and the result page. The change itself is made
 * here through the order and its stock, as the operator's resolution (step 3.4) makes it.
 */
class WebV1BalancePaymentsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val db = TestDatabase.database
    private val stock = InventoryDomainService(panel.purchase.inventories)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val nila by lazy { listOf("black", "olive", "red", "cream", "gray", "pink", "blue").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }

    private fun operator() = panel.caller(PanelTestStack.phone(90), Role.OPERATOR)

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(800 + n))

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    /** One photo drafted and sent by the operator: PHOTO_SENT. */
    private suspend fun ApplicationTestBuilder.photographed(code: OrderCode) {
        val staff = operator()
        val photo = client.post("$API_V1/admin/media") {
            header(HttpHeaders.Authorization, staff.bearer)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "PRE_SHIPMENT")
                        append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                    },
                ),
            )
        }.json().text("id")
        val set = client.put("$API_V1/admin/orders/${code.value}/pre-shipment-media") {
            header(HttpHeaders.Authorization, staff.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"items":[{"mediaId":"$photo"}]}""")
        }
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        val sent = client.post("$API_V1/admin/orders/${code.value}/pre-shipment-media/send") { header(HttpHeaders.Authorization, staff.bearer) }
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
    }

    private fun productOf(variantId: Long): ProductId = transaction(db) {
        ProductId.constructFromPersisted(ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variantId }.single()[ProductVariantQmTbl.productId])
    }

    /**
     * The customer asks for line 1 in [to] and staff carry it out at [more] toman more than the line's price: the order
     * owes [more] on top of what it owed. The stock follows, as the resolution does it.
     */
    private fun owes(code: OrderCode, more: Long, to: Long) = transaction(db) {
        val orderId = panel.purchase.orderQm.findByCode(code)!!.orderId
        val order = panel.purchase.orders.get(orderId)!!
        val now = clock.now
        val color = VariantId.constructFromPersisted(to)
        val product = productOf(to)
        order.requestChange(ChangeRequest(ChangeType.COLOR, 1, color, null), ChangeTarget(color, product), order.userId, now).getOrNull()!!
        val line = order.lines.first { it.lineId == 1 }
        val before = order.lines
        val staff = UserId.constructFromPersisted(operator().userId)
        order.replaceLine(
            line.replacedBy(product, color, line.item, PriceTag.constructFromPersisted(line.priceTag.price + Money.constructFromPersisted(more), null), true, true),
            MaxPerOrder.constructFromPersisted(5), staff, now,
        ).getOrNull()!!
        stock.rebalanceForOrder(order.code, before, order.lines, staff, now).getOrNull()!!.forEach(panel.purchase.inventories::save)
        order.applyChange(null, staff, now).getOrNull()!!
        panel.purchase.orders.save(order)
    }

    private suspend fun ApplicationTestBuilder.pay(code: OrderCode, who: PanelTestStack.Caller, key: String = UUID.randomUUID().toString()): HttpResponse =
        client.post("$API_V1/me/orders/${code.value}/payments") {
            header(HttpHeaders.Authorization, who.bearer)
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

    private suspend fun ApplicationTestBuilder.page(code: OrderCode, who: PanelTestStack.Caller): JsonObject =
        client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, who.bearer) }.json()

    private fun balanceOf(code: OrderCode): Pair<Long, Long> = transaction(db) {
        OrderQmTbl.select(OrderQmTbl.balanceDue, OrderQmTbl.balancePaid).where { OrderQmTbl.code eq code.value }.single()
            .let { it[OrderQmTbl.balanceDue] to it[OrderQmTbl.balancePaid] }
    }

    private fun paymentRow(paymentId: String): Pair<PaymentStatus, PaymentFailureReason?> = transaction(db) {
        PaymentTbl.select(PaymentTbl.status, PaymentTbl.failureReason).where { PaymentTbl.publicId eq paymentId }.single()
            .let { it[PaymentTbl.status] to it[PaymentTbl.failureReason] }
    }

    /** A paid order of [who] with a photo sent, then changed to a color 200,000 dearer: it owes 200,000 and is PROCESSING. */
    private suspend fun ApplicationTestBuilder.owing(who: PanelTestStack.Caller, color: Int = 0): OrderCode {
        val code = panel.paidOrders.paid(who.user.id, nila[color])
        photographed(code)
        owes(code, 200_000, nila[color + 1])
        return code
    }

    @Test
    fun `an order owing 200,000 - 201 for exactly that, paid at the gateway, nothing owed, still PROCESSING, «پرداخت موفق» again, a BALANCE payment on its page`() = panel.app {
        val sara = customer(1)
        val code = owing(sara)
        val before = page(code, sara)
        assertEquals(200_000L, before["balanceDue"]!!.jsonPrimitive.long)
        assertEquals(200_000L, before["totals"]!!.jsonObject["balanceDue"]!!.jsonPrimitive.long)
        assertTrue(before["actions"]!!.jsonObject["canPay"]!!.jsonPrimitive.boolean)
        val paidBefore = before["totals"]!!.jsonObject["payable"]!!.jsonPrimitive.long

        val started = pay(code, sara)

        assertEquals(HttpStatusCode.Created, started.status, started.bodyAsText())
        val init = started.json()
        assertEquals(200_000L, init["amount"]!!.jsonPrimitive.long)
        assertEquals(clock.now.plus(Duration.ofMinutes(20)).toString(), init.text("expiresAt"), "the attempt's window, not the order's time to pay")
        val paymentId = init.text("paymentId")!!
        panel.paidOrders.back(paymentId)

        assertEquals(PaymentStatus.SUCCEEDED to null, paymentRow(paymentId))
        assertEquals(0L to 200_000L, balanceOf(code))
        val after = page(code, sara)
        assertEquals("PROCESSING", after.text("status"))
        assertEquals(0L, after["balanceDue"]!!.jsonPrimitive.long)
        assertEquals(paidBefore + 200_000, after["totals"]!!.jsonObject["payable"]!!.jsonPrimitive.long)
        assertFalse(after["actions"]!!.jsonObject["canPay"]!!.jsonPrimitive.boolean)
        val payment = after["payment"]!!.jsonObject
        assertEquals(listOf("BALANCE", "200000"), listOf(payment.text("purpose"), payment.text("amount")))
        assertEquals("مانده‌ی ۲۰۰٬۰۰۰ تومانی پرداخت شد", after["timeline"]!!.jsonArray.last().jsonObject.text("label"))
        assertEquals(listOf(code, code), panel.purchase.auth.sms.paid.filter { it.first == sara.user.phone }.map { it.second })
        val result = client.get("$API_V1/payments/$paymentId") { header(HttpHeaders.Authorization, sara.bearer) }.json()
        assertEquals("SUCCEEDED", result.text("status"))
        assertFalse(result["canRetry"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `«پرداخت» of a balance sent twice with one Idempotency-Key - the same attempt both times, one BALANCE attempt stored`() = panel.app {
        val sara = customer(6)
        val code = owing(sara)
        val key = UUID.randomUUID().toString()

        val first = pay(code, sara, key)
        val again = pay(code, sara, key)

        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertEquals(first.status to first.json().text("paymentId"), again.status to again.json().text("paymentId"))
        val balanceAttempts = transaction(db) {
            val orderId = panel.purchase.orderQm.findByCode(code)!!.orderId
            panel.purchase.payments.lockAttemptsOf(orderId).count { it.purpose == PaymentPurpose.BALANCE }
        }
        assertEquals(1, balanceAttempts)
    }

    @Test
    fun `a paid order owing nothing is 422 ORDER_ALREADY_PAID, as before`() = panel.app {
        val sara = customer(2)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])

        val refused = pay(code, sara)

        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("ORDER_ALREADY_PAID", refused.json().text("code"))
    }

    @Test
    fun `cancelled at the bank - the result page offers a retry while it is still owed, and the retry is for the balance again`() = panel.app {
        val sara = customer(3)
        val code = owing(sara)
        val paymentId = pay(code, sara).json().text("paymentId")!!

        panel.paidOrders.back(paymentId, paid = false)

        val result = client.get("$API_V1/payments/$paymentId") { header(HttpHeaders.Authorization, sara.bearer) }.json()
        assertTrue(result["canRetry"]!!.jsonPrimitive.boolean, result.toString())
        assertEquals("PROCESSING", page(code, sara).text("status"), "a cancelled attempt doesn't fail a paid order")
        val again = pay(code, sara)
        assertEquals(HttpStatusCode.Created, again.status, again.bodyAsText())
        assertEquals(200_000L, again.json()["amount"]!!.jsonPrimitive.long)
    }

    @Test
    fun `staff change the order again after the attempt started - the old attempt is never confirmed, its money never taken`() = panel.app {
        val sara = customer(4)
        val code = owing(sara)
        val old = pay(code, sara).json().text("paymentId")!!
        photographed(code)
        owes(code, 100_000, nila[2])
        assertEquals(300_000L to 0L, balanceOf(code))

        panel.paidOrders.back(old)

        assertEquals(PaymentStatus.FAILED to PaymentFailureReason.ORDER_NOT_PAYABLE, paymentRow(old))
        assertEquals(300_000L to 0L, balanceOf(code))
        assertEquals(1, panel.purchase.auth.sms.paid.count { it.first == sara.user.phone }, "only the order's own «پرداخت موفق»")
    }

    @Test
    fun `two attempts of one balance both paid at the bank, called back at the same moment, 10 times - paid once, never twice`() = panel.app {
        val sara = customer(5)
        repeat(10) { round ->
            val code = owing(sara, color = round % 5)
            val first = pay(code, sara).json().text("paymentId")!!
            val second = pay(code, sara).json().text("paymentId")!!
            listOf(first, second).forEach { id ->
                val authority = transaction(db) { PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq id }.single()[PaymentTbl.authority]!! }
                panel.purchase.gateway.pay(authority)
            }

            val executor = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            listOf(first, second).forEach { id ->
                executor.submit {
                    start.await()
                    val authority = transaction(db) { PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq id }.single()[PaymentTbl.authority]!! }
                    runBlocking { panel.purchase.handleCallback.execute(HandleGatewayCallbackUc.Cmd("zarinpal", mapOf("Authority" to authority, "Status" to "OK"))) }
                }
            }
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
            // One may have waited for the other (deferred); the job settles it.
            listOf(first, second).forEach { id -> runBlocking { panel.purchase.reconcilePayment.execute(ReconcilePaymentUc.Cmd(PaymentPublicId.constructFromPersisted(id))) } }

            assertEquals(0L to 200_000L, balanceOf(code), "round $round")
            val statuses = listOf(first, second).map { paymentRow(it).first }
            assertEquals(1, statuses.count { it == PaymentStatus.SUCCEEDED }, "round $round: $statuses")
            assertTrue(statuses.none { it == PaymentStatus.VERIFYING || it == PaymentStatus.REDIRECTED }, "round $round: $statuses")
        }
        assertTrue(transaction(db) { PaymentTbl.select(PaymentTbl.purpose).where { PaymentTbl.status eq PaymentStatus.SUCCEEDED }.map { it[PaymentTbl.purpose] } }.contains(PaymentPurpose.BALANCE))
    }
}
