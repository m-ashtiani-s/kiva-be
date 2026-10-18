package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.order.usecase.command.ShipOrderUc
import com.kiva.application.usecase.reservation.usecase.command.ReleaseReservationGroupUc
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ShopSettingsTbl
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
 * Shipping (phase 2 step 2.8) over the real stack on kiva_test: `PUT /admin/orders/{orderCode}/shipment` for one order,
 * for a reservation group as one parcel, and as a correction; and «تحویل شد» through `POST …/status`.
 */
class WebV1ShipmentOnDatabaseTest {

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
    private val mahak by lazy { listOf("black", "lilac", "cream", "brown").map { panel.purchase.variantOf("mahak-shoulder-bag", it) } }

    /** The operator, with a session started now (sessions of before a jump of the clock are over). */
    private fun operator() = panel.caller(PanelTestStack.phone(90), Role.OPERATOR)

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(700 + n))

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.problem(): Pair<String?, String?> = json().let { body ->
        val error = body["errors"]?.takeUnless { it is JsonNull }?.jsonArray?.singleOrNull()?.jsonObject
        if (error != null) error.text("field") to error.text("code") else null to body.text("code")
    }

    private fun shipment(carrier: String = "POST", trackingCode: String = "PT-1234567", shippedAt: Instant? = null, showInDailyList: Boolean? = null) =
        """{"carrier":"$carrier","trackingCode":"$trackingCode"${shippedAt?.let { ",\"shippedAt\":\"$it\"" }.orEmpty()}${showInDailyList?.let { ",\"showInDailyList\":$it" }.orEmpty()}}"""

    private suspend fun ApplicationTestBuilder.ship(code: OrderCode, body: String = shipment()): HttpResponse =
        client.put("$API_V1/admin/orders/${code.value}/shipment") {
            header(HttpHeaders.Authorization, operator().bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.status(code: OrderCode, body: String): HttpResponse =
        client.post("$API_V1/admin/orders/${code.value}/status") {
            header(HttpHeaders.Authorization, operator().bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
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

    /** The customer's «همونه!». */
    private suspend fun ApplicationTestBuilder.approved(code: OrderCode, who: PanelTestStack.Caller) {
        val answered = client.post("$API_V1/me/orders/${code.value}/media-feedback") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"APPROVE"}""")
        }
        assertEquals(HttpStatusCode.OK, answered.status, answered.bodyAsText())
    }

    /** A paid order of [variants], photographed and approved: ready to ship. */
    private suspend fun ApplicationTestBuilder.ready(who: PanelTestStack.Caller, vararg variants: Long): OrderCode {
        val code = panel.paidOrders.paid(who.user.id, *variants)
        photographed(code, variants.size)
        approved(code, who)
        return code
    }

    private fun stockOf(variantId: Long): Pair<Int, Int> = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.onHand, ProductVariantQmTbl.reserved).where { ProductVariantQmTbl.variantId eq variantId }.single()
            .let { it[ProductVariantQmTbl.onHand] to it[ProductVariantQmTbl.reserved] }
    }

    private fun statusOf(code: OrderCode): String = transaction(db) {
        OrderQmTbl.select(OrderQmTbl.status).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.status].name
    }

    private fun groupOf(code: OrderCode): ReservationGroupId = transaction(db) {
        ReservationGroupId.constructFromPersisted(OrderQmTbl.select(OrderQmTbl.reservationGroupId).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.reservationGroupId]!!)
    }

    private fun eventsOf(code: OrderCode, type: String): Long = transaction(db) {
        val orderId = OrderQmTbl.select(OrderQmTbl.orderId).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.orderId]
        EventStoreTbl.selectAll().where { (EventStoreTbl.aggregateId eq orderId.toString()) and (EventStoreTbl.eventType eq type) }.count()
    }

    @Test
    fun `an approved order ships - SHIPPED, on hand and reserved both down, an SMS with the code, the parcel on the card, one change-log row`() = panel.app {
        val sara = customer(1)
        val code = ready(sara, nila[0])
        val (onHand, reserved) = stockOf(nila[0])

        val shipped = ship(code, shipment(trackingCode = "pt-1234567"))

        assertEquals(HttpStatusCode.OK, shipped.status, shipped.bodyAsText())
        val body = shipped.json()
        assertEquals("SHIPPED", body.text("status"))
        val parcel = body["shipment"]!!.jsonObject
        assertEquals("PT-1234567", parcel.text("trackingCode"), "kept in capitals")
        assertEquals("https://tracking.post.ir/?id=PT-1234567", parcel.text("trackingUrl"))
        assertEquals(clock.now.toString(), parcel.text("shippedAt"))
        assertNull(parcel.text("waitingMessage"))
        assertEquals(listOf("DELIVERED"), body["allowedTransitions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(onHand - 1 to reserved - 1, stockOf(nila[0]))
        val sms = panel.purchase.auth.sms.shipped.single()
        assertEquals(listOf(sara.user.phone, code, parcel.text("carrierName"), "PT-1234567"), listOf(sms.to, sms.orderCode, sms.carrierName, sms.trackingCode))
        assertTrue(transaction(db) { OrderQmTbl.select(OrderQmTbl.showInDailyList).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.showInDailyList] })
        val card = client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, sara.bearer) }.json()
        assertEquals("PT-1234567", card["shipment"]!!.jsonObject.text("trackingCode"))
        val rows = transaction(db) { AuditLogTbl.selectAll().where { AuditLogTbl.entityId eq code.value }.orderBy(AuditLogTbl.id).map { it[AuditLogTbl.action] to it[AuditLogTbl.changes].toString() } }
        assertEquals(listOf("ORDER_PHOTO_SENT", "ORDER_SHIPPED"), rows.map { it.first }.filter { it != "ORDER_MEDIA_SET" })
        assertTrue("PT-1234567" in rows.last().second && "POST" in rows.last().second, rows.toString())
    }

    @Test
    fun `what can't ship - another carrier, tomorrow, 8 days ago, a code of 5, an unknown carrier, before the customer's time is over, no photo`() = panel.app {
        val sara = customer(2)
        val code = ready(sara, nila[0])
        val waiting = panel.paidOrders.paid(sara.user.id, nila[1]).also { photographed(it) }
        val unphotographed = panel.paidOrders.paid(sara.user.id, nila[2])

        assertEquals("carrier" to "CARRIER_MISMATCH", ship(code, shipment(carrier = "TIPAX")).problem())
        assertEquals("shippedAt" to "SHIPPED_AT_INVALID", ship(code, shipment(shippedAt = clock.now.plus(Duration.ofDays(1)))).problem())
        assertEquals("shippedAt" to "SHIPPED_AT_INVALID", ship(code, shipment(shippedAt = clock.now.minus(Duration.ofDays(8)))).problem())
        assertEquals("shippedAt" to "SHIPPED_AT_INVALID", ship(code, """{"carrier":"POST","trackingCode":"PT-1234567","shippedAt":"دیروز"}""").problem())
        assertEquals("trackingCode" to "TRACKING_CODE_INVALID", ship(code, shipment(trackingCode = "AB123")).problem())
        assertEquals("trackingCode" to "TRACKING_CODE_REQUIRED", ship(code, """{"carrier":"POST"}""").problem())
        assertEquals("carrier" to "CARRIER_INVALID", ship(code, shipment(carrier = "DHL")).problem())
        val early = ship(waiting)
        assertEquals(HttpStatusCode.UnprocessableEntity, early.status)
        assertEquals("AWAITING_CUSTOMER_FEEDBACK", early.json().text("code"))
        assertEquals(null to "INVALID_STATUS_TRANSITION", ship(unphotographed).problem())
        assertEquals(HttpStatusCode.NotFound, ship(OrderCode.constructFromPersisted("KV-000000")).status)
        assertEquals("PHOTO_SENT", statusOf(code))
        assertEquals(emptyList(), panel.purchase.auth.sms.shipped.toList())
    }

    @Test
    fun `a group ships as one parcel - not while it holds, not while one isn't approved, then all with one code and one SMS, the cancelled one left out`() = panel.app {
        val sara = customer(3)
        val home = panel.paidOrders.savedAddress(sara.user.id)
        val first = panel.paidOrders.paid(sara.user.id, nila[0], reserve = true, addressId = home)
        val second = panel.paidOrders.paid(sara.user.id, nila[1], addressId = home)
        val third = panel.paidOrders.paid(sara.user.id, nila[2], addressId = home)
        val dropped = panel.paidOrders.paid(sara.user.id, nila[3], addressId = home)
        val group = groupOf(first)
        assertEquals(group, groupOf(third))

        assertEquals(null to "INVALID_STATUS_TRANSITION", ship(first).problem(), "the group still holds")

        val cancelled = client.post("$API_V1/me/orders/${dropped.value}/cancel") {
            header(HttpHeaders.Authorization, sara.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"CHANGED_MIND"}""")
        }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        clock.advance(Duration.ofDays(5))
        runBlocking { panel.purchase.releaseReservationGroup.execute(ReleaseReservationGroupUc.Cmd(group)) }
        val again = customer(3)
        listOf(first, second, third).forEach { photographed(it) }
        approved(first, again)
        approved(second, again)

        val notReady = ship(second)
        assertEquals(HttpStatusCode.UnprocessableEntity, notReady.status)
        assertEquals("GROUP_NOT_READY", notReady.json().text("code"))
        assertEquals(listOf(third.value), notReady.json()["meta"]!!.jsonObject["orderCodes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("PHOTO_SENT", "PHOTO_SENT", "PHOTO_SENT"), listOf(first, second, third).map(::statusOf))

        approved(third, again)
        val shipped = ship(second)

        assertEquals(HttpStatusCode.OK, shipped.status, shipped.bodyAsText())
        assertEquals(listOf("SHIPPED", "SHIPPED", "SHIPPED", "CANCELLED"), listOf(first, second, third, dropped).map(::statusOf))
        val codes = transaction(db) {
            OrderQmTbl.select(OrderQmTbl.trackingCode).where { OrderQmTbl.code inList listOf(first, second, third).map { it.value } }.map { it[OrderQmTbl.trackingCode] }
        }
        assertEquals(listOf("PT-1234567"), codes.distinct())
        assertEquals(listOf(second), panel.purchase.auth.sms.shipped.map { it.orderCode }, "one SMS for the parcel")
        assertEquals(listOf(1L, 1L, 1L), listOf(first, second, third).map { eventsOf(it, "OrderShipped") })
        assertEquals(0L, eventsOf(dropped, "OrderShipped"))
    }

    @Test
    fun `a SHIPPED order's parcel is corrected - the code changes, the stock doesn't, no SMS - and a DELIVERED one's is 422`() = panel.app {
        val sara = customer(4)
        val code = ready(sara, nila[0])
        assertEquals(HttpStatusCode.OK, ship(code).status)
        val stock = stockOf(nila[0])

        val corrected = ship(code, shipment(trackingCode = "PT-7654321", showInDailyList = false))

        assertEquals(HttpStatusCode.OK, corrected.status, corrected.bodyAsText())
        assertEquals("PT-7654321", corrected.json()["shipment"]!!.jsonObject.text("trackingCode"))
        assertEquals(stock, stockOf(nila[0]))
        assertEquals(1, panel.purchase.auth.sms.shipped.size)
        assertEquals(1L, eventsOf(code, "OrderShipmentCorrected"))
        assertEquals("carrier" to "CARRIER_MISMATCH", ship(code, shipment(carrier = "TIPAX", trackingCode = "PT-7654321")).problem())
        val texts = transaction(db) { AuditLogTbl.selectAll().where { AuditLogTbl.entityId eq code.value }.map { it[AuditLogTbl.action] } }
        assertEquals(2, texts.count { it == "ORDER_SHIPPED" })

        assertEquals(HttpStatusCode.OK, status(code, """{"status":"DELIVERED"}""").status)
        val late = ship(code, shipment(trackingCode = "PT-1111111"))
        assertEquals(HttpStatusCode.UnprocessableEntity, late.status)
        assertEquals("INVALID_STATUS_TRANSITION", late.json().text("code"))
    }

    @Test
    fun `DELIVERED - delivered now, returnable until 7 days on for a returnable bag, kept when the setting changes, an SMS, and a SHIPPED order can't be cancelled`() = panel.app {
        val sara = customer(5)
        val code = ready(sara, nila[0])
        assertEquals(HttpStatusCode.OK, ship(code).status)

        val refused = status(code, """{"status":"CANCELLED"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals(listOf("DELIVERED"), refused.json()["meta"]!!.jsonObject["allowed"]!!.jsonArray.map { it.jsonPrimitive.content })

        clock.advance(Duration.ofHours(30))
        val delivered = status(code, """{"status":"DELIVERED","note":"به دست خودش رسید"}""")

        assertEquals(HttpStatusCode.OK, delivered.status, delivered.bodyAsText())
        assertEquals(clock.now.toString(), delivered.json()["shipment"]!!.jsonObject.text("deliveredAt"))
        val until = clock.now.plus(Duration.ofDays(7))
        val returnable = { transaction(db) { OrderQmTbl.select(OrderQmTbl.returnableUntil).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.returnableUntil] } }
        assertEquals(until, returnable()?.toInstant())
        transaction(db) { ShopSettingsTbl.update { it[ShopSettingsTbl.returnWindowDays] = 14 } }
        assertEquals(until, returnable()?.toInstant(), "fixed when it was delivered")
        assertEquals(listOf(sara.user.phone to code), panel.purchase.auth.sms.delivered.toList())
        val page = client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, customer(5).bearer) }.json()
        assertEquals("تحویل شد" to "به دست خودش رسید", page["timeline"]!!.jsonArray.last().jsonObject.let { it.text("label") to it.text("note") })
        assertEquals(emptyList(), status(code, """{"status":"DELIVERED"}""").json()["meta"]!!.jsonObject["allowed"]!!.jsonArray)
        transaction(db) { ShopSettingsTbl.update { it[ShopSettingsTbl.returnWindowDays] = 7 } }
    }

    @Test
    fun `the same order shipped twice at once - one succeeds, the stock goes once`() = panel.app {
        val sara = customer(6)
        val code = ready(sara, nila[0])
        val (onHand, _) = stockOf(nila[0])
        val staff = PanelActor(operator().user.id, null)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val outcomes = listOf("PT-1111111", "PT-2222222").map { tracking ->
                pool.submit<Boolean> {
                    start.await()
                    runBlocking { panel.adminOrders.ship.execute(ShipOrderUc.Cmd(code.value, "POST", tracking, null, null, staff)) }.isSuccess
                }
            }
            start.countDown()
            outcomes.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1L, eventsOf(code, "OrderShipped"))
        assertEquals(onHand - 1, stockOf(nila[0]).first)
    }

    @Test
    fun `A (color 1, color 2) and B (color 2, color 1) ship at once, 50 times - never a deadlock`() = panel.app {
        val staff = PanelActor(operator().user.id, null)
        (nila + mahak).forEach { variant ->
            val restocked = runBlocking { panel.adminInventory.adjustInventory.execute(AdjustInventoryUc.Cmd(variant, 40, "RESTOCK", null, staff)) }
            assertTrue(restocked.isSuccess, restocked.toString())
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(50) { round ->
                val sara = customer(20 + round)
                val one = nila[round % nila.size]
                val two = mahak[round % mahak.size]
                val a = ready(sara, one, two)
                val b = ready(sara, two, one)
                val start = CountDownLatch(1)
                val outcomes = listOf(a to "PT-A$round-0000", b to "PT-B$round-0000").map { (code, tracking) ->
                    pool.submit<Boolean> {
                        start.await()
                        runBlocking { panel.adminOrders.ship.execute(ShipOrderUc.Cmd(code.value, "POST", tracking, null, null, staff)) }.isSuccess
                    }
                }
                start.countDown()

                assertEquals(listOf(true, true), outcomes.map { it.get(30, TimeUnit.SECONDS) }, "round $round")
                assertEquals(listOf("SHIPPED", "SHIPPED"), listOf(statusOf(a), statusOf(b)), "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
