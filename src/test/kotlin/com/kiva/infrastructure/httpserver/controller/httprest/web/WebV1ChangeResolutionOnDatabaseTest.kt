package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.UpdateVariantUc
import com.kiva.application.usecase.reservation.usecase.command.ReleaseReservationGroupUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefundTbl
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
import kotlin.test.assertTrue

/**
 * The operator's resolution of a change request (phase 3 step 3.4) over the real stack on kiva_test:
 * `POST /admin/orders/{orderCode}/change-request/resolution`, what it does to the order, its stock and its money, the SMS,
 * the change log, and shipping after it.
 */
class WebV1ChangeResolutionOnDatabaseTest {

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

    private val nilaColors = listOf("black", "olive", "red", "cream", "gray", "pink", "blue")
    private val nila by lazy { nilaColors.map { panel.purchase.variantOf("nila-crossbody-bag", it) } }
    private val mahak by lazy { listOf("black", "lilac", "cream", "brown").map { panel.purchase.variantOf("mahak-shoulder-bag", it) } }

    private fun operator() = panel.caller(PanelTestStack.phone(90), Role.OPERATOR)

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(1000 + n))

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.problems(): Set<Pair<String?, String?>> {
        assertEquals(HttpStatusCode.BadRequest, status, bodyAsText())
        return json()["errors"]!!.jsonArray.map { it.jsonObject.text("field") to it.jsonObject.text("code") }.toSet()
    }

    private suspend fun HttpResponse.refusal(): String? {
        assertEquals(HttpStatusCode.UnprocessableEntity, status, bodyAsText())
        return json().text("code")
    }

    /** One photo per line ([lines] of them) drafted and sent by the operator. */
    private suspend fun ApplicationTestBuilder.photographed(code: OrderCode, lines: Int = 1) {
        val staff = operator()
        val items = (1..lines).map { line ->
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
            if (lines == 1) """{"mediaId":"$photo"}""" else """{"mediaId":"$photo","orderItemId":$line}"""
        }
        val set = client.put("$API_V1/admin/orders/${code.value}/pre-shipment-media") {
            header(HttpHeaders.Authorization, staff.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"items":$items}""")
        }
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        val sent = client.post("$API_V1/admin/orders/${code.value}/pre-shipment-media/send") { header(HttpHeaders.Authorization, staff.bearer) }
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.feedback(code: OrderCode, who: PanelTestStack.Caller, body: String): HttpResponse =
        client.post("$API_V1/me/orders/${code.value}/media-feedback") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** The customer asks for [type] on [line], to [desired]. */
    private suspend fun ApplicationTestBuilder.ask(code: OrderCode, who: PanelTestStack.Caller, type: String, line: Int? = 1, desired: Long? = null, note: String? = null) {
        val fields = listOfNotNull(
            "\"decision\":\"REQUEST_CHANGE\"", "\"changeType\":\"$type\"", line?.let { "\"orderItemId\":$it" }, desired?.let { "\"desiredVariantId\":$it" },
            note?.let { "\"note\":\"$it\"" },
        )
        val asked = feedback(code, who, "{${fields.joinToString(",")}}")
        assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.resolve(code: OrderCode, body: String, staff: PanelTestStack.Caller = operator()): HttpResponse =
        client.post("$API_V1/admin/orders/${code.value}/change-request/resolution") {
            header(HttpHeaders.Authorization, staff.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.ship(code: OrderCode): HttpResponse =
        client.put("$API_V1/admin/orders/${code.value}/shipment") {
            header(HttpHeaders.Authorization, operator().bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"carrier":"POST","trackingCode":"PT-1234567"}""")
        }

    private suspend fun ApplicationTestBuilder.page(code: OrderCode, who: PanelTestStack.Caller): JsonObject =
        client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, who.bearer) }.json()

    private suspend fun ApplicationTestBuilder.pay(code: OrderCode, who: PanelTestStack.Caller): HttpResponse =
        client.post("$API_V1/me/orders/${code.value}/payments") {
            header(HttpHeaders.Authorization, who.bearer)
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

    /** What the shop can still sell of [variantId]: on hand minus reserved. */
    private fun available(variantId: Long): Int = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.onHand, ProductVariantQmTbl.reserved).where { ProductVariantQmTbl.variantId eq variantId }.single()
            .let { it[ProductVariantQmTbl.onHand] - it[ProductVariantQmTbl.reserved] }
    }

    private fun productOf(variantId: Long): Long = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variantId }.single()[ProductVariantQmTbl.productId]
    }

    /** Nila's [color] gets a price of its own, as the panel's color form sets it. */
    private fun priced(color: Int, price: Long) {
        val staff = PanelActor(UserId.constructFromPersisted(panel.callerWith(Role.ADMIN).userId), null)
        val form = ProductInput.Variant(colorKey = nilaColors[color], priceOverride = price)
        runBlocking { panel.adminProducts.updateVariant.execute(UpdateVariantUc.Cmd(productOf(nila[color]).toString(), nila[color].toString(), form, staff)) }
            .getOrNull() ?: error("not priced")
    }

    private fun refundsOf(code: OrderCode): List<Triple<Long, String, String>> = transaction(db) {
        val orderId = OrderQmTbl.select(OrderQmTbl.orderId).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.orderId]
        RefundTbl.selectAll().where { RefundTbl.orderId eq orderId }.map { Triple(it[RefundTbl.amount], it[RefundTbl.status].name, it[RefundTbl.refundSource].name) }
    }

    private fun auditOf(code: OrderCode): List<Pair<String, String>> = transaction(db) {
        AuditLogTbl.selectAll().where { AuditLogTbl.entityId eq code.value }.orderBy(AuditLogTbl.id).map { it[AuditLogTbl.action] to it[AuditLogTbl.changes].toString() }
    }

    /** A paid Nila (its black) of [who], its photo sent, and a COLOR change to Nila's [toColor] asked. */
    private suspend fun ApplicationTestBuilder.asked(who: PanelTestStack.Caller, toColor: Int = 1): OrderCode {
        val code = panel.paidOrders.paid(who.user.id, nila[0])
        photographed(code)
        ask(code, who, "COLOR", desired = nila[toColor])
        return code
    }

    @Test
    fun `turned down - a note is needed, with it PROCESSING, photos WAITING, the customer reads why, «درخواستت بررسی شد» without it, one change-log row`() = panel.app {
        val sara = customer(1)
        val code = asked(sara)

        assertEquals(setOf("note" to "NOTE_REQUIRED"), resolve(code, """{"decision":"REJECT"}""").problems())
        assertEquals(setOf("variantId" to "VARIANT_NOT_ALLOWED"), resolve(code, """{"decision":"REJECT","variantId":${nila[2]},"note":"نه"}""").problems())
        val rejected = resolve(code, """{"decision":"REJECT","note":"زیتونی تموم شده"}""")

        assertEquals(HttpStatusCode.OK, rejected.status, rejected.bodyAsText())
        assertEquals(listOf("PROCESSING", "WAITING"), listOf(rejected.json().text("status"), rejected.json()["preShipmentMediaDetail"]!!.jsonObject.text("status")))
        val mine = page(code, sara)
        val resolution = mine["preShipmentMediaDetail"]!!.jsonObject["feedback"]!!.jsonObject["resolution"]!!.jsonObject
        assertEquals(listOf("REJECT", null, "زیتونی تموم شده"), listOf(resolution.text("decision"), resolution.text("variantId"), resolution.text("note")))
        val last = mine["timeline"]!!.jsonArray.last().jsonObject
        assertEquals(listOf("درخواستت رد شد", "زیتونی تموم شده"), listOf(last.text("label"), last.text("note")))
        assertEquals(listOf(sara.user.phone to code), panel.purchase.auth.sms.changeResolved.toList())
        val row = auditOf(code).last()
        assertEquals("ORDER_CHANGE_RESOLVED", row.first)
        assertTrue("REJECT" in row.second && "COLOR" in row.second && "زیتونی" !in row.second, row.second)
    }

    @Test
    fun `a dearer color - the line swapped, the old color's hold back and the new one held, 200,000 owed, canPay - it doesn't ship until paid, then it does`() = panel.app {
        val sara = customer(2)
        priced(1, 1_650_000)
        val code = asked(sara)
        val before = available(nila[0]) to available(nila[1])

        val applied = resolve(code, """{"decision":"APPLY","note":"زیتونیش رو گذاشتیم","notifyCustomer":false}""")

        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        val order = applied.json()
        assertEquals(listOf(nila[1].toString(), "1650000"), order["items"]!!.jsonArray.single().jsonObject.let { listOf(it.text("variantId"), it.text("unitPrice")) })
        assertEquals(200_000L, order["balanceDue"]!!.jsonPrimitive.long)
        assertEquals(before.first + 1 to before.second - 1, available(nila[0]) to available(nila[1]))
        assertEquals(emptyList(), panel.purchase.auth.sms.changeResolved.toList(), "notifyCustomer false")
        val mine = page(code, sara)
        assertTrue(mine["actions"]!!.jsonObject["canPay"]!!.jsonPrimitive.boolean)
        assertEquals("رنگ عوض شد", mine["timeline"]!!.jsonArray.last().jsonObject.text("label"))
        val resolution = mine["preShipmentMediaDetail"]!!.jsonObject["feedback"]!!.jsonObject["resolution"]!!.jsonObject
        assertEquals(listOf("APPLY", nila[1].toString()), listOf(resolution.text("decision"), resolution.text("variantId")))
        assertTrue(auditOf(code).last().second.let { nila[1].toString() in it && nila[0].toString() in it }, auditOf(code).last().second)

        photographed(code)
        assertEquals(HttpStatusCode.OK, feedback(code, sara, """{"decision":"APPROVE"}""").status)
        val owing = ship(code)
        assertEquals("BALANCE_DUE", owing.refusal())
        assertEquals(200_000L, owing.json()["meta"]?.jsonObject?.get("balanceDue")?.jsonPrimitive?.long ?: 200_000L)
        val paymentId = pay(code, sara).json().text("paymentId")!!
        panel.paidOrders.back(paymentId)
        val shipped = ship(code)
        assertEquals(HttpStatusCode.OK, shipped.status, shipped.bodyAsText())
        assertEquals("SHIPPED", shipped.json().text("status"))
    }

    @Test
    fun `a cheaper color - nothing owed, and a PENDING refund of 200,000 from the change request`() = panel.app {
        val sara = customer(3)
        priced(2, 1_250_000)
        val code = asked(sara, toColor = 2)

        val applied = resolve(code, """{"decision":"APPLY"}""")

        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals(0L, applied.json()["balanceDue"]!!.jsonPrimitive.long)
        assertEquals(listOf(Triple(200_000L, "PENDING", "CHANGE_REQUEST")), refundsOf(code))
        assertEquals(listOf(sara.user.phone to code), panel.purchase.auth.sms.changeResolved.toList())
    }

    @Test
    fun `a color with none left - 422 QUANTITY_EXCEEDS_STOCK with what is left, and nothing changed, the old hold still there`() = panel.app {
        val sara = customer(4)
        val code = asked(sara, toColor = 3)
        val buyer = customer(5)
        repeat(available(nila[3])) { panel.paidOrders.paid(buyer.user.id, nila[3]) }
        val before = available(nila[0])

        val refused = resolve(code, """{"decision":"APPLY"}""")

        assertEquals("QUANTITY_EXCEEDS_STOCK", refused.refusal())
        assertEquals(0L, refused.json()["meta"]!!.jsonObject["availableQuantity"]!!.jsonPrimitive.long)
        assertEquals(before, available(nila[0]))
        assertEquals("CHANGE_REQUESTED", page(code, sara).text("status"))
        assertEquals(emptyList(), refundsOf(code))
    }

    @Test
    fun `staff choose another color than asked - it must fit the change, be in the shop, and exist`() = panel.app {
        val sara = customer(6)
        val code = asked(sara)

        assertEquals(setOf("variantId" to "DESIRED_VARIANT_INVALID"), resolve(code, """{"decision":"APPLY","variantId":${mahak[0]}}""").problems())
        assertEquals(setOf("variantId" to "DESIRED_VARIANT_INVALID"), resolve(code, """{"decision":"APPLY","variantId":987654321}""").problems())
        val applied = resolve(code, """{"decision":"APPLY","variantId":${nila[4]}}""")
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals(nila[4].toString(), applied.json()["items"]!!.jsonArray.single().jsonObject.text("variantId"))
    }

    @Test
    fun `CANCEL_ITEM of the only line - the order CANCELLED as the panel cancels it - its stock back, all it paid to refund, «لغو شد»`() = panel.app {
        val sara = customer(7)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        photographed(code)
        ask(code, sara, "CANCEL_ITEM")
        val before = available(nila[0])

        assertEquals(setOf("variantId" to "VARIANT_NOT_ALLOWED"), resolve(code, """{"decision":"APPLY","variantId":${nila[1]}}""").problems())
        val applied = resolve(code, """{"decision":"APPLY","note":"به درخواست مشتری"}""")

        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals("CANCELLED", applied.json().text("status"))
        assertEquals(before + 1, available(nila[0]))
        val paid = page(code, sara)["totals"]!!.jsonObject["payable"]!!.jsonPrimitive.long
        assertEquals(listOf(Triple(paid, "PENDING", "CANCELLATION")), refundsOf(code))
        assertEquals(listOf(sara.user.phone to code), panel.purchase.auth.sms.cancelled.toList())
        assertEquals(emptyList(), panel.purchase.auth.sms.changeResolved.toList())
        assertEquals("ORDER_CHANGE_RESOLVED", auditOf(code).last().first)
    }

    @Test
    fun `CANCEL_ITEM of one of two lines - the line gone, its stock back, what was paid for it to refund, PROCESSING`() = panel.app {
        val sara = customer(8)
        val code = panel.paidOrders.paid(sara.user.id, nila[0], mahak[0])
        photographed(code, lines = 2)
        ask(code, sara, "CANCEL_ITEM", line = 2)
        val before = available(mahak[0])

        val applied = resolve(code, """{"decision":"APPLY"}""")

        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        val order = applied.json()
        assertEquals(listOf("1"), order["items"]!!.jsonArray.map { it.jsonObject.text("id") })
        assertEquals("PROCESSING", order.text("status"))
        assertEquals(before + 1, available(mahak[0]))
        assertEquals(listOf(Triple(1_890_000L, "PENDING", "CHANGE_REQUEST")), refundsOf(code), "Mahak's 1,890,000, no code, shipping kept")
        assertEquals("یه قلم حذف شد", page(code, sara)["timeline"]!!.jsonArray.last().jsonObject.text("label"))
    }

    @Test
    fun `OTHER - a note of what was done is needed, then only the status comes back`() = panel.app {
        val sara = customer(9)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        photographed(code)
        ask(code, sara, "OTHER", line = null, note = "بند بلندتر")

        assertEquals(setOf("note" to "NOTE_REQUIRED"), resolve(code, """{"decision":"APPLY"}""").problems())
        assertEquals(setOf("variantId" to "VARIANT_NOT_ALLOWED"), resolve(code, """{"decision":"APPLY","variantId":${nila[1]},"note":"گذاشتیم"}""").problems())
        val applied = resolve(code, """{"decision":"APPLY","note":"بند بلندتر گذاشتیم"}""")
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals(listOf("PROCESSING", "0"), listOf(applied.json().text("status"), applied.json().text("balanceDue")))
    }

    @Test
    fun `nothing to resolve is 422 INVALID_STATUS_TRANSITION, an unknown decision 400, a code nobody has 404, a CATALOG_MANAGER 403`() = panel.app {
        val sara = customer(10)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        photographed(code)

        val nothing = resolve(code, """{"decision":"APPLY"}""")
        assertEquals("INVALID_STATUS_TRANSITION", nothing.refusal())
        assertEquals(setOf("decision" to "RESOLUTION_DECISION_INVALID"), resolve(code, """{"decision":"MAYBE"}""").problems())
        assertEquals(HttpStatusCode.NotFound, resolve(OrderCode.constructFromPersisted("KV-000000"), """{"decision":"APPLY"}""").status)
        assertEquals(HttpStatusCode.Forbidden, resolve(code, """{"decision":"APPLY"}""", panel.callerWith(Role.CATALOG_MANAGER)).status)
        assertEquals(HttpStatusCode.OK, resolve(asked(sara), """{"decision":"REJECT","note":"نه"}""", panel.callerWith(Role.ADMIN)).status)
    }

    @Test
    fun `a payment of its balance being confirmed - 422 PAYMENT_IN_PROGRESS, nothing changed`() = panel.app {
        val sara = customer(11)
        priced(1, 1_650_000)
        val code = asked(sara)
        assertEquals(HttpStatusCode.OK, resolve(code, """{"decision":"APPLY"}""").status)
        photographed(code)
        ask(code, sara, "OTHER", line = null, note = "یه چیز دیگه")
        val paymentId = pay(code, sara).json().text("paymentId")!!
        transaction(db) {
            val payment = panel.purchase.payments.lockAttemptsOf(panel.purchase.payments.find(com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId.constructFromPersisted(paymentId))!!.orderId)
                .first { it.publicId.value == paymentId }
            payment.startVerifying(clock.now)
            panel.purchase.payments.save(payment)
        }

        assertEquals("PAYMENT_IN_PROGRESS", resolve(code, """{"decision":"APPLY","note":"انجام شد"}""").refusal())
        assertEquals("CHANGE_REQUESTED", page(code, sara).text("status"))
    }

    @Test
    fun `resolution and the customer starting to pay the balance at the same moment, 5 times - one goes first, and no money is ever unaccounted for`() = panel.app {
        val sara = customer(12)
        priced(1, 1_650_000)
        priced(2, 1_350_000)
        repeat(5) { round ->
            // 200,000 owed, then a change to a color 300,000 cheaper than the line now: applied, it owes nothing and gives 100,000 back.
            val code = asked(sara)
            assertEquals(HttpStatusCode.OK, resolve(code, """{"decision":"APPLY","notifyCustomer":false}""").status)
            photographed(code)
            ask(code, sara, "COLOR", desired = nila[2])

            val executor = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            val outcomes = listOf(
                executor.submit<HttpResponse> { start.await(); runBlocking { resolve(code, """{"decision":"APPLY","notifyCustomer":false}""") } },
                executor.submit<HttpResponse> { start.await(); runBlocking { pay(code, sara) } },
            )
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
            val (resolved, paying) = outcomes.map { it.get() }

            assertEquals(HttpStatusCode.OK, resolved.status, "round $round: ${resolved.bodyAsText()}")
            // The payment started first (an attempt of 200,000) or after (nothing owed any more).
            val attempt = if (paying.status == HttpStatusCode.Created) paying.json().text("paymentId") else null.also { assertEquals("ORDER_ALREADY_PAID", paying.refusal()) }
            attempt?.let { panel.paidOrders.back(it) }

            val (owed, paidMore) = transaction(db) {
                OrderQmTbl.select(OrderQmTbl.balanceDue, OrderQmTbl.balancePaid).where { OrderQmTbl.code eq code.value }.single()
                    .let { it[OrderQmTbl.balanceDue] to it[OrderQmTbl.balancePaid] }
            }
            assertEquals(0L to 0L, owed to paidMore, "round $round: nothing owed, nothing paid for the balance")
            assertEquals(listOf(Triple(100_000L, "PENDING", "CHANGE_REQUEST")), refundsOf(code), "round $round")
            attempt?.let { id ->
                val status = transaction(db) { PaymentTbl.select(PaymentTbl.status).where { PaymentTbl.publicId eq id }.single()[PaymentTbl.status] }
                assertEquals(PaymentStatus.FAILED, status, "round $round: its money never taken")
            }
        }
    }

    @Test
    fun `a reservation group with an order owing its balance - GROUP_NOT_READY naming it, none shipped`() = panel.app {
        val sara = customer(13)
        priced(1, 1_650_000)
        val home = panel.paidOrders.savedAddress(sara.user.id)
        val first = panel.paidOrders.paid(sara.user.id, nila[0], reserve = true, addressId = home)
        val second = panel.paidOrders.paid(sara.user.id, nila[0], addressId = home)
        val group = transaction(db) {
            ReservationGroupId.constructFromPersisted(OrderQmTbl.select(OrderQmTbl.reservationGroupId).where { OrderQmTbl.code eq first.value }.single()[OrderQmTbl.reservationGroupId]!!)
        }
        clock.advance(Duration.ofDays(5))
        runBlocking { panel.purchase.releaseReservationGroup.execute(ReleaseReservationGroupUc.Cmd(group)) }
        val again = customer(13)
        listOf(first, second).forEach { photographed(it) }
        ask(second, again, "COLOR", desired = nila[1])
        assertEquals(HttpStatusCode.OK, resolve(second, """{"decision":"APPLY"}""").status)
        photographed(second)
        listOf(first, second).forEach { assertEquals(HttpStatusCode.OK, feedback(it, again, """{"decision":"APPROVE"}""").status) }

        val notReady = ship(first)

        assertEquals("GROUP_NOT_READY", notReady.refusal())
        assertEquals(listOf(second.value), notReady.json()["meta"]!!.jsonObject["orderCodes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("PHOTO_SENT", page(first, again).text("status"))
    }
}
