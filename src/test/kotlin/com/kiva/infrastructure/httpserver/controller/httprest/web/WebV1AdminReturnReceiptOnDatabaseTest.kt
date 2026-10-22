package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.returnrequest.usecase.command.ReceiveReturnUc
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnCode
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.seed.DiscountCodeRow
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parcel of a return reaching the warehouse, its refund and its closing over kiva_test (roadmap step 7.5): returns
 * asked and approved the way the shop and the panel do it, the stock's history, the refund of a return through
 * `POST /admin/orders/{orderCode}/refunds` and `PATCH /admin/refunds/{refundId}`, every answer held against the
 * contract's AdminReturnRequest.
 */
class WebV1AdminReturnReceiptOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val returns = AskedReturns(panel, clock)
    private val sms get() = panel.purchase.auth.sms

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val nila by lazy { listOf("black", "olive").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

    private fun staff(role: Role) = panel.caller(PanelTestStack.phone(80 + Role.entries.indexOf(role)), role)

    /** The warehouse's operator, by name: who the stock's history says brought the bags back. */
    private fun storekeeper() = panel.caller(PanelTestStack.phone(95), Role.OPERATOR, name = "رضا انباری")

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.errors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private fun JsonObject.allowed(): List<String> = getValue("allowedTransitions").jsonArray.map { it.jsonPrimitive.content }

    private suspend fun ApplicationTestBuilder.send(path: String, body: String, who: PanelTestStack.Caller = staff(Role.OPERATOR)): HttpResponse =
        client.post("$API_V1$path") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.approve(returnId: Long) =
        send("/admin/returns/$returnId/decision", """{"approve":true}""").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

    private suspend fun ApplicationTestBuilder.receipt(returnId: Long, items: String, who: PanelTestStack.Caller = storekeeper()) =
        send("/admin/returns/$returnId/receipt", """{"items":$items,"note":"دسته‌ی کیف دوم کنده شده"}""", who)

    private suspend fun ApplicationTestBuilder.close(returnId: Long, body: String = """{"reason":"بازبینی نشون داد کیف استفاده شده"}""") =
        send("/admin/returns/$returnId/close", body)

    private suspend fun ApplicationTestBuilder.refund(code: OrderCode, body: String): HttpResponse = send("/admin/orders/${code.value}/refunds", body, staff(Role.ADMIN))

    private suspend fun ApplicationTestBuilder.follow(refundId: String, body: String): HttpResponse =
        client.patch("$API_V1/admin/refunds/$refundId") {
            header(HttpHeaders.Authorization, staff(Role.ADMIN).bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.adminReturn(returnId: Long): JsonObject =
        client.get("$API_V1/admin/returns?size=100") { header(HttpHeaders.Authorization, staff(Role.OPERATOR).bearer) }.json()
            .getValue("items").jsonArray.map { it.jsonObject }.single { it.text("id") == returnId.toString() }

    private suspend fun ApplicationTestBuilder.myOrder(code: OrderCode, who: PanelTestStack.Caller): JsonObject =
        client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, returns.fresh(who).bearer) }.json()

    /** The stock's history of [variantId]: (delta, reason, note, createdBy), newest first. */
    private suspend fun ApplicationTestBuilder.stockHistory(variantId: Long): List<List<String?>> =
        client.get("$API_V1/admin/inventory/adjustments?variantId=$variantId") { header(HttpHeaders.Authorization, staff(Role.ADMIN).bearer) }.json()
            .getValue("items").jsonArray.map { it.jsonObject.let { row -> listOf(row.text("delta"), row.text("reason"), row.text("note"), row.text("createdBy")) } }

    private fun auditActionsOf(returnId: Long): List<String> = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.entityType eq "RETURN") and (AuditLogTbl.entityId eq returnId.toString()) }.orderBy(AuditLogTbl.id).map { it[AuditLogTbl.action] }
    }

    private fun codeOf(returnId: Long) = ReturnCode.forId(ReturnId.constructFromPersisted(returnId))

    private fun restock(variantId: Long, bags: Int) = transaction(db) {
        val inventory = panel.purchase.inventories.get(VariantId.constructFromPersisted(variantId))!!
        inventory.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, UserId.SYSTEM, clock.instant())
        panel.purchase.inventories.save(inventory)
    }

    private val adminReturnKeys = ContractSchemas.propertiesOf("ReturnRequest") + ContractSchemas.propertiesOf("AdminReturnRequest")

    /** Sara's order of black and olive, both lines asked back: the order, the return. */
    private suspend fun ApplicationTestBuilder.twoLinesBack(reason: String = "MANUFACTURING_DEFECT", refundMethod: String? = null): Pair<OrderCode, Long> {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0], nila[1]) }
        val returnId = with(returns) { asked(code, sara, reason, items = listOf(1 to 1, 2 to 1), refundMethod = refundMethod) }
        approve(returnId)
        return code to returnId
    }

    @Test
    fun `a parcel of two, one sound and one torn - RECEIVED, the order RETURNED, the sound one back on the shelf as RETURNED by the operator, the torn one not`() = panel.app {
        val (code, returnId) = twoLinesBack()
        val blackBefore = panel.purchase.availableOf(nila[0])
        val oliveBefore = panel.purchase.availableOf(nila[1])

        val received = receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1},{"orderItemId":2,"receivedQuantity":1,"restockQuantity":0}]""")

        assertEquals(HttpStatusCode.OK, received.status, received.bodyAsText())
        val ret = received.json()
        assertEquals(adminReturnKeys, ret.keys)
        assertEquals("RECEIVED", ret.text("status"))
        assertEquals(clock.now.toString(), ret.text("receivedAt"))
        assertEquals(listOf("REFUNDED", "CLOSED"), ret.allowed())
        assertTrue(ret.text("suggestedRefund")!!.toLong() > 0)
        val lines = ret.getValue("receipt").jsonObject.getValue("items").jsonArray.map { it.jsonObject.let { l -> listOf(l.text("orderItemId"), l.text("receivedQuantity"), l.text("restockQuantity")) } }
        assertEquals(listOf(listOf("1", "1", "1"), listOf("2", "1", "0")), lines)
        assertEquals("دسته‌ی کیف دوم کنده شده", ret.getValue("receipt").jsonObject.text("note"))
        assertEquals("RETURNED", myOrder(code, customer(1)).text("status"))
        assertEquals(blackBefore + 1, panel.purchase.availableOf(nila[0]))
        assertEquals(oliveBefore, panel.purchase.availableOf(nila[1]))
        assertEquals(listOf("1", "RETURNED", codeOf(returnId).value, "رضا انباری"), stockHistory(nila[0]).first())
        assertTrue(stockHistory(nila[1]).none { it[1] == "RETURNED" })
        assertEquals(listOf("RETURN_DECIDED", "RETURN_RECEIVED"), auditActionsOf(returnId))
        assertTrue(sms.returnsClosed.isEmpty() && sms.refundsCompleted.isEmpty(), "a receipt sends nothing")
    }

    @Test
    fun `a receipt without one of the lines is RECEIPT_ITEMS_MISMATCH with the lines - more on the shelf than came, or more than went, 400 on its field`() = panel.app {
        val (_, returnId) = twoLinesBack()

        val missing = receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1}]""")
        val twice = receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1},{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1}]""")
        val tooMany = receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":2},{"orderItemId":2,"receivedQuantity":3,"restockQuantity":0}]""")
        val shape = receipt(returnId, """[{"orderItemId":1,"receivedQuantity":21},{"receivedQuantity":-1,"restockQuantity":0}]""")

        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals(listOf("items RECEIPT_ITEMS_MISMATCH"), missing.errors())
        assertEquals(listOf("1", "2"), missing.json().getValue("errors").jsonArray.single().jsonObject.getValue("meta").jsonObject.getValue("expectedItemIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("items RECEIPT_ITEMS_MISMATCH"), twice.errors())
        assertEquals(listOf("items.0.restockQuantity RESTOCK_EXCEEDS_RECEIVED", "items.1.receivedQuantity RECEIVED_QUANTITY_EXCEEDS"), tooMany.errors())
        assertEquals(
            listOf("items.0.receivedQuantity NUMBER_OUT_OF_RANGE", "items.0.restockQuantity FIELD_REQUIRED", "items.1.orderItemId FIELD_REQUIRED", "items.1.receivedQuantity NUMBER_OUT_OF_RANGE"),
            shape.errors(),
        )
        assertEquals("APPROVED", adminReturn(returnId).text("status"))
    }

    @Test
    fun `the operator records the receipt though the stock's form is not theirs - and a return not yet approved is 422`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara) }
        val operator = storekeeper()

        val tooEarly = receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1}]""", operator)
        approve(returnId)
        val received = receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1}]""", operator)
        val byHand = send("/admin/inventory/adjustments", """{"variantId":${nila[0]},"delta":1,"reason":"RESTOCK"}""", operator)

        assertEquals(HttpStatusCode.UnprocessableEntity, tooEarly.status)
        assertEquals("INVALID_STATUS_TRANSITION", tooEarly.json().text("code"))
        assertEquals(HttpStatusCode.OK, received.status, received.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, byHand.status)
        assertEquals(HttpStatusCode.Forbidden, receipt(returnId, "[]", customer(1)).status)
    }

    @Test
    fun `the suggested refund takes the discount code's share off and never the shipping`() = panel.app {
        panel.purchase.discountCode(DiscountCodeRow("RT10", DiscountType.PERCENT, 10, "ده درصد"))
        restock(nila[0], 5)
        val sara = customer(1)
        val code = panel.paidOrders.paid(sara.user.id, nila[0], quantity = 2, discountCode = "RT10")
        with(returns.shippedOrders) { shipped(code, returns.fresh(sara), "PT-8000001") }
        clock.advance(java.time.Duration.ofDays(2))
        send("/admin/orders/${code.value}/status", """{"status":"DELIVERED"}""").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val returnId = with(returns) { asked(code, sara, items = listOf(1 to 2)) }
        approve(returnId)
        val order = transaction(db) { panel.purchase.orderQm.findByCode(code)!! }
        val lineTotal = order.lines.single().totals.total.toman
        val codeDiscount = order.totals.codeDiscount.toman
        assertTrue(codeDiscount > 0, "the code took something off")

        val received = receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1}]""")

        // One of two came back: half of what the line was paid, the code's share off; the shipping stays.
        assertEquals((lineTotal - codeDiscount) / 2, received.json().text("suggestedRefund")!!.toLong())
    }

    @Test
    fun `a return's refund - another order's return 400, before RECEIVED 422, a second one 422 RETURN_REFUND_EXISTS, after the first FAILED another 201`() = panel.app {
        val (code, returnId) = twoLinesBack(refundMethod = "BANK_TRANSFER")
        val reza = customer(2)
        val othersOrder = with(returns) { delivered(reza, nila[0]) }

        val othersReturn = refund(othersOrder, """{"amount":100000,"reason":"مرجوعی","returnId":$returnId}""")
        val notYet = refund(code, """{"amount":100000,"reason":"مرجوعی","returnId":$returnId}""")
        receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1},{"orderItemId":2,"receivedQuantity":1,"restockQuantity":1}]""")
        val first = refund(code, """{"amount":100000,"reason":"مرجوعی","returnId":$returnId}""")
        val second = refund(code, """{"amount":100000,"reason":"مرجوعی","returnId":$returnId}""")
        val firstId = first.json().text("id")!!
        follow(firstId, """{"status":"FAILED","note":"شماره شبا تأیید نشد"}""").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val afterFailed = refund(code, """{"amount":100000,"reason":"مرجوعی","returnId":$returnId,"method":"BANK_TRANSFER"}""")

        assertEquals(HttpStatusCode.BadRequest, othersReturn.status)
        assertEquals(listOf("returnId RETURN_NOT_FOUND"), othersReturn.errors())
        assertEquals(HttpStatusCode.UnprocessableEntity, notYet.status)
        assertEquals("INVALID_STATUS_TRANSITION", notYet.json().text("code"))
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertEquals(listOf(returnId.toString(), "BANK_TRANSFER"), first.json().let { listOf(it.text("returnId"), it.text("method")) }, "the return's way back")
        assertEquals(HttpStatusCode.UnprocessableEntity, second.status)
        assertEquals("RETURN_REFUND_EXISTS", second.json().text("code"))
        assertEquals(HttpStatusCode.Created, afterFailed.status, afterFailed.bodyAsText())
        val ret = adminReturn(returnId)
        assertEquals("RECEIVED", ret.text("status"), "a FAILED refund leaves it RECEIVED")
        assertEquals(afterFailed.json().text("id"), ret.getValue("refund").jsonObject.text("id"), "the latest refund")
        assertEquals(emptyList(), ret.allowed(), "a refund is open")
    }

    @Test
    fun `a return's refund COMPLETED - the return REFUNDED, the order REFUNDED, its refunded total, «پولت برگشت»`() = panel.app {
        val (code, returnId) = twoLinesBack("CHANGED_MIND", refundMethod = "BANK_TRANSFER")
        receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1},{"orderItemId":2,"receivedQuantity":1,"restockQuantity":1}]""")
        val suggested = adminReturn(returnId).text("suggestedRefund")!!.toLong()
        val opened = refund(code, """{"amount":$suggested,"reason":"مرجوعی","returnId":$returnId}""")
        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())

        val completed = follow(opened.json().text("id")!!, """{"status":"COMPLETED","referenceId":"140507181234567"}""")

        assertEquals(HttpStatusCode.OK, completed.status, completed.bodyAsText())
        val ret = adminReturn(returnId)
        assertEquals(listOf("REFUNDED", "BANK_TRANSFER"), listOf(ret.text("status"), ret.getValue("refund").jsonObject.text("method")), "the customer asked for a transfer")
        assertEquals(emptyList(), ret.allowed())
        assertNull(ret.text("suggestedRefund"))
        val page = myOrder(code, customer(1))
        assertEquals("REFUNDED", page.text("status"))
        assertEquals(suggested.toString(), page.getValue("totals").jsonObject.text("refunded"))
        assertEquals(listOf(customer(1).user.phone to code), sms.refundsCompleted.toList())
    }

    @Test
    fun `closing with a refund open is 422 RETURN_REFUND_OPEN - from RECEIVED it is CLOSED, the order DELIVERED, never returned again, «مرجوعی بسته شد»`() = panel.app {
        val (code, returnId) = twoLinesBack()
        receipt(returnId, """[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1},{"orderItemId":2,"receivedQuantity":0,"restockQuantity":0}]""")
        val opened = refund(code, """{"amount":100000,"reason":"مرجوعی","returnId":$returnId}""")

        val whileOpen = close(returnId)
        follow(opened.json().text("id")!!, """{"status":"FAILED"}""")
        val noReason = close(returnId, """{"reason":"نه"}""")
        val closed = close(returnId)

        assertEquals(HttpStatusCode.UnprocessableEntity, whileOpen.status)
        assertEquals("RETURN_REFUND_OPEN", whileOpen.json().text("code"))
        assertEquals(listOf("reason CLOSE_REASON_REQUIRED"), noReason.errors())
        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
        val ret = closed.json()
        assertEquals(listOf("CLOSED", "بازبینی نشون داد کیف استفاده شده"), listOf(ret.text("status"), ret.text("closeReason")))
        assertEquals(clock.now.toString(), ret.text("closedAt"))
        val page = myOrder(code, customer(1))
        assertEquals("DELIVERED", page.text("status"))
        assertEquals("false", page.getValue("actions").jsonObject.text("canReturn"), "closed, it is never returned again, its window open or not")
        assertEquals(listOf(customer(1).user.phone to codeOf(returnId)), sms.returnsClosed.toList())
        assertEquals("RETURN_CLOSED", auditActionsOf(returnId).last())
        assertEquals(HttpStatusCode.UnprocessableEntity, with(returns) { ask(code, customer(1), """{"items":[{"orderItemId":1,"quantity":1}],"reason":"CHANGED_MIND"}""") }.status)
    }

    @Test
    fun `closing a REQUESTED return is 422 - an APPROVED one that never came closes, quietly with notifyCustomer false`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara) }

        val requested = close(returnId)
        approve(returnId)
        val closed = close(returnId, """{"reason":"مرسوله‌ای از طرفت به انبار نرسید","notifyCustomer":false}""")

        assertEquals(HttpStatusCode.UnprocessableEntity, requested.status)
        assertEquals("INVALID_STATUS_TRANSITION", requested.json().text("code"))
        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
        assertEquals("CLOSED", closed.json().text("status"))
        assertEquals("DELIVERED", myOrder(code, sara).text("status"))
        assertTrue(sms.returnsClosed.isEmpty())
    }

    @Test
    fun `two operators record one parcel at the same moment, 10 times - one 200, one 422, the stock up once`() = panel.app {
        val sara = customer(1)
        val operator = storekeeper()
        restock(nila[0], 20)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { n ->
                val code = with(returns) { delivered(sara, nila[0]) }
                val returnId = with(returns) { asked(code, sara) }
                approve(returnId)
                val before = panel.purchase.availableOf(nila[0])
                val start = CountDownLatch(1)
                val results = (1..2).map {
                    pool.submit<Any> {
                        start.await()
                        runBlocking {
                            panel.adminReturns.receive.execute(
                                ReceiveReturnUc.Cmd(returnId.toString(), listOf(ReceiveReturnUc.LineFields(1, 1, 1)), null, PanelActor(operator.user.id, null)),
                            )
                        }
                    }
                }
                start.countDown()
                val outcomes = results.map { (it.get(30, TimeUnit.SECONDS) as Result<*, *>).failureOrNull()?.code ?: "RECEIVED" }
                assertEquals(listOf("INVALID_STATUS_TRANSITION", "RECEIVED"), outcomes.sorted(), "round $n")
                assertEquals(before + 1, panel.purchase.availableOf(nila[0]), "round $n")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
