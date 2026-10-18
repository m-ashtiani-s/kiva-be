package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.order.usecase.command.CancelMyOrderUc
import com.kiva.application.usecase.order.usecase.command.ChangeOrderStatusUc
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefundTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReservationGroupTbl
import com.kiva.infrastructure.seed.DiscountCodeRow
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cancelling an order (phase 2 step 2.7): the customer's own (`POST /me/orders/{orderCode}/cancel`) while nothing has
 * moved, the panel's (`POST /admin/orders/{orderCode}/status` with CANCELLED) until it ships, and everything that goes
 * with both, on kiva_test.
 */
class WebV1OrderCancellationOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val db = TestDatabase.database

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
    private val mahakBlack by lazy { panel.purchase.variantOf("mahak-shoulder-bag", "black") }
    private val operator by lazy { panel.callerWith(Role.OPERATOR) }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(500 + n))

    private suspend fun ApplicationTestBuilder.cancel(code: OrderCode, who: PanelTestStack.Caller, body: String = """{"reason":"CHANGED_MIND"}"""): HttpResponse =
        client.post("$API_V1/me/orders/${code.value}/cancel") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.status(code: OrderCode, body: String, caller: PanelTestStack.Caller = operator): HttpResponse =
        client.post("$API_V1/admin/orders/${code.value}/status") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun soldOf(variantId: Long): Pair<Int, Int> = transaction(db) {
        val row = ProductVariantQmTbl.select(ProductVariantQmTbl.productId, ProductVariantQmTbl.soldCount).where { ProductVariantQmTbl.variantId eq variantId }.single()
        val product = ProductQmTbl.select(ProductQmTbl.soldCount).where { ProductQmTbl.productId eq row[ProductVariantQmTbl.productId] }.single()
        product[ProductQmTbl.soldCount] to row[ProductVariantQmTbl.soldCount]
    }

    private fun refundsOf(code: OrderCode): List<Triple<Long, String, String>> = transaction(db) {
        val orderId = OrderQmTbl.select(OrderQmTbl.orderId).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.orderId]
        RefundTbl.selectAll().where { RefundTbl.orderId eq orderId }.map { Triple(it[RefundTbl.amount], it[RefundTbl.status].name, it[RefundTbl.method].name) }
    }

    @Test
    fun `the customer cancels a PROCESSING order - stock back, sold taken back, the code's use back, a full PENDING refund, an SMS, and the answer`() = panel.app {
        panel.purchase.discountCode(DiscountCodeRow("ONCE", DiscountType.FIXED, 100_000, "یک بار", usageLimit = 1))
        val sara = customer(1)
        val available = panel.purchase.availableOf(nila[0])
        val sold = soldOf(nila[0])
        val code = panel.paidOrders.paid(sara.user.id, nila[0], discountCode = "ONCE")
        val payable = panel.purchase.orderQm.let { transaction(db) { it.findByCode(code)!!.totals.payable.toman } }
        assertTrue(client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, sara.bearer) }.json()["actions"]!!.jsonObject["canCancel"]!!.jsonPrimitive.boolean)

        val cancelled = cancel(code, sara, """{"reason":"FOUND_CHEAPER","note":"ارزون‌تر پیدا کردم"}""")

        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        val answer = cancelled.json()
        assertEquals("CANCELLED", answer["order"]!!.jsonObject.text("status"))
        assertEquals(false, answer["order"]!!.jsonObject["actions"]!!.jsonObject["canCancel"]!!.jsonPrimitive.boolean)
        assertEquals(payable, answer["refund"]!!.jsonObject["amount"]!!.jsonPrimitive.long)
        assertEquals("BANK_TRANSFER" to "PENDING", answer["refund"]!!.jsonObject.let { it.text("method") to it.text("status") })
        assertTrue(answer.text("message")!!.contains("۷۲"))
        assertEquals(available, panel.purchase.availableOf(nila[0]))
        assertEquals(sold, soldOf(nila[0]))
        assertEquals(listOf(Triple(payable, "PENDING", "BANK_TRANSFER")), refundsOf(code))
        assertEquals(listOf(sara.user.phone to code), panel.purchase.auth.sms.cancelled.toList())
        // Its one use is back: someone else can use it now.
        panel.paidOrders.paid(customer(2).user.id, nila[1], discountCode = "ONCE")
        val page = client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, sara.bearer) }.json()
        assertEquals("لغو شد" to "ارزون‌تر پیدا کردم", page["timeline"]!!.jsonArray.last().jsonObject.let { it.text("label") to it.text("note") })
    }

    @Test
    fun `an order with nothing paid is cancelled without a refund`() = panel.app {
        panel.purchase.discountCode(DiscountCodeRow("GIFT100", DiscountType.PERCENT, 100, "هدیه‌ی کامل"))
        val sara = customer(3)
        val code = panel.paidOrders.paid(sara.user.id, mahakBlack, quantity = 2, discountCode = "GIFT100")

        val cancelled = cancel(code, sara)

        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        assertEquals(JsonNull, cancelled.json()["refund"])
        assertEquals("سفارش لغو شد.", cancelled.json().text("message"))
        assertEquals(emptyList(), refundsOf(code))
    }

    @Test
    fun `after a photo the customer can't cancel - 422 ORDER_NOT_CANCELLABLE - the panel can, with its note on the timeline`() = panel.app {
        val sara = customer(4)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        val photo = client.post("$API_V1/admin/media") {
            header(HttpHeaders.Authorization, operator.bearer)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "PRE_SHIPMENT")
                        append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                    },
                ),
            )
        }.json().text("id")
        client.put("$API_V1/admin/orders/${code.value}/pre-shipment-media") {
            header(HttpHeaders.Authorization, operator.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"items":[{"mediaId":"$photo"}]}""")
        }
        client.post("$API_V1/admin/orders/${code.value}/pre-shipment-media/send") { header(HttpHeaders.Authorization, operator.bearer) }

        val refused = cancel(code, sara)
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("ORDER_NOT_CANCELLABLE", refused.json().text("code"))

        val byPanel = status(code, """{"status":"CANCELLED","note":"مشتری زنگ زد","notifyCustomer":false}""")
        assertEquals(HttpStatusCode.OK, byPanel.status, byPanel.bodyAsText())
        assertEquals("CANCELLED", byPanel.json().text("status"))
        assertEquals(emptyList(), byPanel.json()["allowedTransitions"]!!.jsonArray)
        assertEquals(emptyList(), panel.purchase.auth.sms.cancelled.toList(), "notifyCustomer false: no SMS")
        val page = client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, sara.bearer) }.json()
        assertEquals("مشتری زنگ زد", page["timeline"]!!.jsonArray.last().jsonObject.text("note"))

        val again = cancel(code, sara)
        assertEquals("ORDER_NOT_CANCELLABLE", again.json().text("code"))
        val twice = status(code, """{"status":"CANCELLED"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, twice.status)
        assertEquals(emptyList(), twice.json()["meta"]!!.jsonObject["allowed"]!!.jsonArray)
    }

    @Test
    fun `the panel moves only where allowed - an unknown status 400, PHOTO_SENT 422 with what is allowed, an unpaid order 422`() = panel.app {
        val sara = customer(5)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        val unpaid = panel.paidOrders.place(sara.user.id, nila[1]).first

        assertEquals("status" to "ORDER_STATUS_INVALID", status(code, """{"status":"LOST"}""").json()["errors"]!!.jsonArray.single().jsonObject.let { it.text("field") to it.text("code") })
        val photoSent = status(code, """{"status":"PHOTO_SENT"}""").json()
        assertEquals("INVALID_STATUS_TRANSITION", photoSent.text("code"))
        assertEquals(listOf("CANCELLED"), photoSent["meta"]!!.jsonObject["allowed"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(HttpStatusCode.UnprocessableEntity, status(unpaid, """{"status":"CANCELLED"}""").status)
        assertEquals("note" to "NOTE_REQUIRED", cancel(code, sara, """{"reason":"OTHER"}""").json()["errors"]!!.jsonArray.single().jsonObject.let { it.text("field") to it.text("code") })
        assertEquals("reason" to "CANCEL_REASON_INVALID", cancel(code, sara, """{"reason":"BORED"}""").json()["errors"]!!.jsonArray.single().jsonObject.let { it.text("field") to it.text("code") })
        assertEquals(HttpStatusCode.NotFound, cancel(code, customer(6)).status)
        assertEquals(HttpStatusCode.Forbidden, status(code, """{"status":"CANCELLED"}""", panel.callerWith(Role.CATALOG_MANAGER)).status)
    }

    @Test
    fun `the customer and an operator cancel at once, 20 times - one OrderCancelled, one refund, the stock back once`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(20) { round ->
                val sara = customer(10 + round)
                val variant = nila[round % nila.size]
                val available = panel.purchase.availableOf(variant)
                val code = panel.paidOrders.paid(sara.user.id, variant)
                val start = CountDownLatch(1)
                val byCustomer = pool.submit<Boolean> {
                    start.await()
                    runBlocking { panel.purchase.myOrderUseCases.cancelMyOrder.execute(CancelMyOrderUc.Cmd(sara.user.id, code.value, "CHANGED_MIND", null)) }.isSuccess
                }
                val byStaff = pool.submit<Boolean> {
                    start.await()
                    runBlocking {
                        panel.adminOrders.changeStatus.execute(ChangeOrderStatusUc.Cmd(code.value, "CANCELLED", null, false, PanelActor(operator.user.id, null)))
                    }.isSuccess
                }
                start.countDown()
                val outcomes = listOf(byCustomer.get(30, TimeUnit.SECONDS), byStaff.get(30, TimeUnit.SECONDS))

                assertEquals(1, outcomes.count { it }, "round $round: $outcomes")
                assertEquals(1, refundsOf(code).size, "round $round")
                assertEquals(available, panel.purchase.availableOf(variant), "round $round")
                val cancellations = transaction(db) {
                    val orderId = OrderQmTbl.select(OrderQmTbl.orderId).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.orderId]
                    EventStoreTbl.selectAll().where { (EventStoreTbl.aggregateId eq orderId.toString()) and (EventStoreTbl.eventType eq "OrderCancelled") }.count()
                }
                assertEquals(1L, cancellations, "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `cancelling a reservation's first order leaves the group holding, and its codes no longer have it`() = panel.app {
        val sara = customer(7)
        val home = panel.paidOrders.savedAddress(sara.user.id)
        val held = panel.paidOrders.paid(sara.user.id, mahakBlack, reserve = true, addressId = home)
        val joined = panel.paidOrders.paid(sara.user.id, nila[0], addressId = home)

        assertEquals(HttpStatusCode.OK, cancel(held, sara).status)

        val group = transaction(db) { ReservationGroupTbl.selectAll().single { it[ReservationGroupTbl.userId] == sara.userId } }
        assertEquals("HOLDING", group[ReservationGroupTbl.status].name)
        val reservation = client.get("$API_V1/me/orders/${joined.value}") { header(HttpHeaders.Authorization, sara.bearer) }.json()["reservation"]!!.jsonObject
        assertEquals(listOf(joined.value), reservation["groupOrderCodes"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a customer whose only order was cancelled still gets a first-purchase code`() = panel.app {
        panel.purchase.discountCode(DiscountCodeRow("WELCOME", DiscountType.FIXED, 100_000, "خرید اول", firstOrderOnly = true, requiresLogin = true))
        val sara = customer(8)
        val first = panel.paidOrders.paid(sara.user.id, nila[0])
        cancel(first, sara)

        val second = panel.paidOrders.paid(sara.user.id, nila[1], discountCode = "WELCOME")

        assertEquals(100_000L, transaction(db) { panel.purchase.orderQm.findByCode(second)!!.totals.codeDiscount.toman })
    }

    @Test
    fun `the customer's cancel leaves nothing in the change log - the panel's leaves ORDER_STATUS_CHANGED`() = panel.app {
        val sara = customer(9)
        val byCustomer = panel.paidOrders.paid(sara.user.id, nila[0])
        val byPanel = panel.paidOrders.paid(sara.user.id, nila[1])

        cancel(byCustomer, sara)
        status(byPanel, """{"status":"CANCELLED"}""")

        val rows = transaction(db) {
            AuditLogTbl.selectAll().where { AuditLogTbl.entityId inList listOf(byCustomer.value, byPanel.value) }.map {
                Triple(it[AuditLogTbl.entityId], it[AuditLogTbl.action], it[AuditLogTbl.changes]?.toString())
            }
        }
        assertEquals(1, rows.size, "$rows")
        assertEquals(byPanel.value to "ORDER_STATUS_CHANGED", rows.single().let { it.first to it.second })
        assertTrue(""""from":"PROCESSING"""" in rows.single().third!! && """"to":"CANCELLED"""" in rows.single().third!!, rows.toString())
        assertEquals(listOf(sara.user.phone to byCustomer, sara.user.phone to byPanel), panel.purchase.auth.sms.cancelled.toList())
    }
}
