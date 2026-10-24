package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.returnrequest.usecase.command.CreateReturnUc
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.tbl.MediaTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReturnRequestTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asking for a return and «مرجوعی‌های من» over kiva_test (roadmap step 7.3): orders paid, photographed, shipped and
 * delivered the way the shop does it, the customer's own photos uploaded with `POST /me/uploads`, every answer held
 * against the contract's ReturnRequest.
 */
class WebV1MyReturnsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val shippedOrders = ShippedOrders(panel)

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

    /** [who] with a session started now: one from before a jump of the clock is over. */
    private fun fresh(who: PanelTestStack.Caller) = panel.caller(who.user.activePhone.value)

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.errors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private var parcels = 0

    /** An order of [who] of [variants] (one each), taken to DELIVERED by the panel: returnable for 7 days from now. */
    private suspend fun ApplicationTestBuilder.delivered(who: PanelTestStack.Caller, vararg variants: Long): OrderCode {
        val code = panel.paidOrders.paid(who.user.id, *variants)
        with(shippedOrders) { shipped(code, fresh(who), "PT-${5_000_000 + ++parcels}", lines = variants.size) }
        clock.advance(Duration.ofDays(2))
        val delivered = client.post("$API_V1/admin/orders/${code.value}/status") {
            header(HttpHeaders.Authorization, shippedOrders.operator().bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DELIVERED"}""")
        }
        assertEquals(HttpStatusCode.OK, delivered.status, delivered.bodyAsText())
        return code
    }

    /** A photo of the problem, uploaded by [who] for a return; its id. */
    private suspend fun ApplicationTestBuilder.photo(who: PanelTestStack.Caller, purpose: String = "RETURN_EVIDENCE"): String {
        val uploaded = client.post("$API_V1/me/uploads") {
            header(HttpHeaders.Authorization, fresh(who).bearer)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", purpose)
                        append("file", FakeMediaProcessor.photo(1200, 900), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, uploaded.status, uploaded.bodyAsText())
        return uploaded.json().text("id")!!
    }

    private suspend fun ApplicationTestBuilder.askReturn(code: OrderCode, who: PanelTestStack.Caller?, body: String): HttpResponse =
        client.post("$API_V1/me/orders/${code.value}/returns") {
            who?.let { header(HttpHeaders.Authorization, fresh(it).bearer) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun body(reason: String, mediaIds: List<String> = emptyList(), description: String? = null, items: String = """[{"orderItemId":1,"quantity":1}]""") =
        """{"items":$items,"reason":"$reason","mediaIds":${mediaIds.map { "\"$it\"" }},"description":${description?.let { "\"$it\"" } ?: "null"}}"""

    private suspend fun ApplicationTestBuilder.orderPage(code: OrderCode, who: PanelTestStack.Caller): JsonObject =
        client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, fresh(who).bearer) }.json()

    private suspend fun ApplicationTestBuilder.canReturnInList(who: PanelTestStack.Caller): Boolean =
        client.get("$API_V1/me/orders") { header(HttpHeaders.Authorization, fresh(who).bearer) }.json()
            .getValue("items").jsonArray.single().jsonObject.getValue("actions").jsonObject.text("canReturn") == "true"

    private fun JsonObject.canReturn(): Boolean = getValue("actions").jsonObject.text("canReturn") == "true"

    /** [bags] more of [variantId] in the warehouse, so 20 orders find enough. */
    private fun restock(variantId: Long, bags: Int) = transaction(db) {
        val inventory = panel.purchase.inventories.get(VariantId.constructFromPersisted(variantId))!!
        inventory.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, UserId.SYSTEM, clock.instant())
        panel.purchase.inventories.save(inventory)
    }

    private fun attachedOn(mediaId: String): Instant? = transaction(db) {
        MediaTbl.select(MediaTbl.attachedOn).where { MediaTbl.mediaId eq mediaId }.single()[MediaTbl.attachedOn]?.toInstant()
    }

    @Test
    fun `a delivered order, one line not as pictured with Sara's photo - 201 RT-, the shop pays, the order RETURN_REQUESTED, the photo used`() = panel.app {
        val sara = customer(1)
        val code = delivered(sara, nila[0])
        val evidence = photo(sara)

        val asked = askReturn(code, sara, body("NOT_AS_PICTURED", listOf(evidence), "رنگش با عکس فرق داشت"))

        assertEquals(HttpStatusCode.Created, asked.status, asked.bodyAsText())
        val ret = asked.json()
        assertEquals(ContractSchemas.propertiesOf("ReturnRequest"), ret.keys)
        assertTrue(ret.text("code")!!.matches(Regex("^RT-\\d{4,}$")))
        assertEquals(listOf("REQUESTED", "در حال بررسی", "NOT_AS_PICTURED", "KIVA", "ORIGINAL_PAYMENT", code.value), listOf("status", "statusLabel", "reason", "shippingPaidBy", "refundMethod", "orderCode").map { ret.text(it) })
        assertEquals("رنگش با عکس فرق داشت", ret.text("description"))
        assertNull(ret.text("instructions"), "no instructions before it is approved")
        listOf("rejectionReason", "pickup", "refund", "decidedAt", "receivedAt", "closedAt", "closeReason").forEach { assertEquals(JsonNull, ret[it], it) }
        val item = ret.getValue("items").jsonArray.single().jsonObject
        assertEquals(listOf("1", "1"), listOf(item.text("orderItemId"), item.text("quantity")))
        assertEquals("black", item.getValue("color").jsonObject.text("key"))
        assertEquals(evidence, ret.getValue("media").jsonArray.single().jsonObject.text("id"))
        assertEquals(clock.now, attachedOn(evidence))
        val page = orderPage(code, sara)
        assertEquals("RETURN_REQUESTED", page.text("status"))
        assertEquals(ret, page.getValue("returns").jsonArray.single().jsonObject)
    }

    @Test
    fun `the shop's fault without a photo is EVIDENCE_REQUIRED, other without a word DESCRIPTION_REQUIRED - changed mind without a photo is 201, the customer paying`() = panel.app {
        val sara = customer(1)
        val code = delivered(sara, nila[0])

        val noPhoto = askReturn(code, sara, body("MANUFACTURING_DEFECT"))
        val noWord = askReturn(code, sara, body("OTHER"))
        val changedMind = askReturn(code, sara, """{"items":[{"orderItemId":1,"quantity":1}],"reason":"CHANGED_MIND","refundMethod":"BANK_TRANSFER","iban":"IR820540102680020817909002"}""")

        assertEquals(HttpStatusCode.BadRequest, noPhoto.status)
        assertEquals(listOf("mediaIds EVIDENCE_REQUIRED"), noPhoto.errors())
        assertEquals(listOf("description DESCRIPTION_REQUIRED"), noWord.errors())
        assertEquals(HttpStatusCode.Created, changedMind.status, changedMind.bodyAsText())
        assertEquals(listOf("CUSTOMER", "BANK_TRANSFER"), changedMind.json().let { listOf(it.text("shippingPaidBy"), it.text("refundMethod")) })
    }

    @Test
    fun `someone else's photo is the same MEDIA_NOT_FOUND as one that isn't there - someone else's order is 404 - no login 401`() = panel.app {
        val sara = customer(1)
        val reza = customer(2)
        val code = delivered(sara, nila[0])
        val rezas = photo(reza)

        val othersPhoto = askReturn(code, sara, body("WRONG_ITEM", listOf(rezas)))
        val missing = askReturn(code, sara, body("WRONG_ITEM", listOf("med_" + "Z".repeat(22))))
        val othersOrder = askReturn(code, reza, body("WRONG_ITEM", listOf(rezas)))
        val anonymous = askReturn(code, null, body("CHANGED_MIND"))

        assertEquals(listOf("mediaIds.0 MEDIA_NOT_FOUND"), othersPhoto.errors())
        assertEquals(othersPhoto.status to othersPhoto.errors(), missing.status to missing.errors(), "nothing tells someone else's photo from none")
        assertEquals(HttpStatusCode.NotFound, othersOrder.status)
        assertEquals("ORDER_NOT_FOUND", othersOrder.json().text("code"))
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertNull(attachedOn(rezas))
    }

    @Test
    fun `a photo uploaded for something else is MEDIA_NOT_FOUND too`() = panel.app {
        val sara = customer(1)
        val code = delivered(sara, nila[0])
        val reviewPhoto = photo(sara, purpose = "REVIEW_PHOTO")

        assertEquals(listOf("mediaIds.0 MEDIA_NOT_FOUND"), askReturn(code, sara, body("WRONG_ITEM", listOf(reviewPhoto))).errors())
    }

    private val wellFormed = "med_" + "A".repeat(22)

    @Test
    fun `every problem of the shape at once, each on its field`() = panel.app {
        val sara = customer(1)
        val code = delivered(sara, nila[0])

        val wrong = askReturn(
            code, sara,
            """{"items":[{"orderItemId":0,"quantity":21},{"quantity":1},{"orderItemId":1,"quantity":1},{"orderItemId":1,"quantity":1}],
               "reason":"TOO_BIG","description":"${"ا".repeat(1001)}","mediaIds":["nope","$wellFormed","$wellFormed"],
               "refundMethod":"STORE_CREDIT"}""",
        )
        val nothing = askReturn(code, sara, "{}")

        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals(
            listOf(
                "items.0.orderItemId ORDER_ITEM_NOT_FOUND", "items.0.quantity QUANTITY_INVALID", "items.1.orderItemId FIELD_REQUIRED",
                "items.3.orderItemId LIST_ITEM_DUPLICATE", "reason RETURN_REASON_INVALID", "description TEXT_TOO_LONG", "mediaIds.0 MEDIA_ID_INVALID",
                "mediaIds.2 LIST_ITEM_DUPLICATE", "refundMethod REFUND_METHOD_INVALID",
            ),
            wrong.errors(),
        )
        assertEquals(listOf("items FIELD_REQUIRED", "reason FIELD_REQUIRED"), nothing.errors())
    }

    @Test
    fun `a line the order doesn't have, more than bought - 400 on their fields, the most there is in meta`() = panel.app {
        val sara = customer(1)
        val code = delivered(sara, nila[0])

        val wrong = askReturn(code, sara, body("CHANGED_MIND", items = """[{"orderItemId":2,"quantity":1},{"orderItemId":1,"quantity":2}]"""))

        assertEquals(listOf("items.0.orderItemId ORDER_ITEM_NOT_FOUND", "items.1.quantity RETURN_QUANTITY_EXCEEDS"), wrong.errors())
        assertEquals("1", wrong.json().getValue("errors").jsonArray[1].jsonObject.getValue("meta").jsonObject.text("maxQuantity"))
    }

    @Test
    fun `a second return is 422 ORDER_NOT_RETURNABLE - and on day 8 RETURN_WINDOW_EXPIRED with the window's end`() = panel.app {
        val sara = customer(1)
        val first = delivered(sara, nila[0])
        val late = delivered(sara, nila[1])
        val returnableUntil = clock.now.plus(Duration.ofDays(7)).toString()
        askReturn(first, sara, body("CHANGED_MIND")).also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }

        val again = askReturn(first, sara, body("CHANGED_MIND"))
        clock.advance(Duration.ofDays(8))
        val tooLate = askReturn(late, sara, body("CHANGED_MIND"))

        assertEquals(HttpStatusCode.UnprocessableEntity, again.status)
        assertEquals("ORDER_NOT_RETURNABLE", again.json().text("code"))
        assertEquals(HttpStatusCode.UnprocessableEntity, tooLate.status)
        assertEquals("RETURN_WINDOW_EXPIRED", tooLate.json().text("code"))
        assertEquals(returnableUntil, tooLate.json().getValue("meta").jsonObject.text("returnableUntil"))
    }

    @Test
    fun `«مرجوع کردن» in the list and on the page - on when delivered, off while a return is open`() = panel.app {
        val sara = customer(1)
        val code = delivered(sara, nila[0])

        assertTrue(canReturnInList(sara))
        assertTrue(orderPage(code, sara).canReturn())
        askReturn(code, sara, body("CHANGED_MIND")).also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }

        assertEquals(false, canReturnInList(sara))
        assertEquals(false, orderPage(code, sara).canReturn())
    }

    @Test
    fun `«مرجوعی‌های من» is a plain array of the customer's own, newest first`() = panel.app {
        val sara = customer(1)
        val reza = customer(2)
        val older = delivered(sara, nila[0])
        val newer = delivered(sara, nila[1])
        val rezas = delivered(reza, nila[0])
        askReturn(older, sara, body("CHANGED_MIND"))
        clock.advance(Duration.ofMinutes(1))
        askReturn(newer, sara, body("CHANGED_MIND"))
        askReturn(rezas, reza, body("CHANGED_MIND"))

        val list = client.get("$API_V1/me/returns") { header(HttpHeaders.Authorization, fresh(sara).bearer) }

        assertEquals(HttpStatusCode.OK, list.status)
        val returns = Json.parseToJsonElement(list.bodyAsText()) as JsonArray
        assertEquals(listOf(newer.value, older.value), returns.map { it.jsonObject.text("orderCode") })
        returns.forEach { assertEquals(ContractSchemas.propertiesOf("ReturnRequest"), it.jsonObject.keys) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1/me/returns").status)
    }

    @Test
    fun `two returns of one order asked at the same moment, 20 times - always exactly one`() = panel.app {
        val sara = customer(1)
        nila.forEach { restock(it, 20) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(20) { n ->
                // Each raced right after its delivery: the test clock moves two days per delivery, past an earlier one's window.
                val code = delivered(sara, nila[n % 2])
                val start = CountDownLatch(1)
                val results = (1..2).map {
                    pool.submit<Any> {
                        start.await()
                        runBlocking {
                            panel.myReturns.createReturn.execute(
                                CreateReturnUc.Cmd(sara.user.id, code.value, listOf(CreateReturnUc.ItemFields(1, 1)), "CHANGED_MIND", null, null, null),
                            )
                        }
                    }
                }
                start.countDown()
                // The order's lock makes the second wait and find the order RETURN_REQUESTED: an answer, never a clash.
                val outcomes = results.map { (it.get(30, TimeUnit.SECONDS) as Result<*, *>).failureOrNull()?.code ?: "CREATED" }
                assertEquals(listOf("CREATED", "ORDER_NOT_RETURNABLE"), outcomes.sorted(), "order ${code.value}")
                val stored = transaction(db) {
                    ReturnRequestTbl.select(ReturnRequestTbl.returnId).where { ReturnRequestTbl.orderCode eq code.value }.count()
                }
                assertEquals(1L, stored, "order ${code.value}")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
