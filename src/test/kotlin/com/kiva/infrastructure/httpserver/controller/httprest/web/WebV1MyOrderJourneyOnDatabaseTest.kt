package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * «سفارش‌های من» complete (phase 2 step 2.10): one order taken the whole road (paid, photos sent, approved, shipped,
 * delivered), its page read after each step and held against the contract's schemas and the values of that step; and
 * the panel's `totalSpent` without an order cancelled and given back in full.
 */
class WebV1MyOrderJourneyOnDatabaseTest {

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

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.objects(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

    /** The order's page, its every object holding exactly its schema's fields. */
    private suspend fun ApplicationTestBuilder.page(code: OrderCode, who: PanelTestStack.Caller): JsonObject {
        val response = client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, who.bearer) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val page = response.json()
        assertEquals(ContractSchemas.propertiesOf("OrderDetail"), page.keys, "OrderDetail")
        assertEquals(ContractSchemas.propertiesOf("Shipment"), page.obj("shipment").keys, "Shipment")
        assertEquals(ContractSchemas.propertiesOf("PreShipmentMediaSummary"), page.obj("preShipmentMedia").keys, "PreShipmentMediaSummary")
        assertEquals(ContractSchemas.propertiesOf("PreShipmentMedia"), page.obj("preShipmentMediaDetail").keys, "PreShipmentMedia")
        assertEquals(ContractSchemas.propertiesOf("OrderTotals"), page.obj("totals").keys, "OrderTotals")
        page.obj("progress").objects("steps").forEach { assertEquals(ContractSchemas.propertiesOf("OrderProgressStep"), it.keys, "OrderProgressStep") }
        page.objects("items").forEach { assertEquals(ContractSchemas.propertiesOf("OrderItem"), it.keys, "OrderItem") }
        page.objects("timeline").forEach { assertEquals(ContractSchemas.propertiesOf("OrderEvent"), it.keys, "OrderEvent") }
        return page
    }

    /** Each step's state and moment. */
    private fun JsonObject.progress(): List<Pair<String?, String?>> = obj("progress").objects("steps").map { it.text("state") to it.text("at") }

    /** The buttons that are on. */
    private fun JsonObject.actionsOn(): List<String> = obj("actions").entries.filter { it.value.jsonPrimitive.content == "true" }.map { it.key }

    @Test
    fun `one order the whole road - after each step its page is what the contract and the step say`() = panel.app {
        val sara = customer(1)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        val paidAt = clock.now.toString()

        val paid = page(code, sara)
        assertEquals("PROCESSING", paid.text("status"))
        assertEquals(listOf("CURRENT" to paidAt, "UPCOMING" to null, "UPCOMING" to null, "UPCOMING" to null), paid.progress())
        assertEquals(listOf("canCancel"), paid.actionsOn())
        assertEquals(listOf("WAITING", "0"), listOf(paid.obj("preShipmentMedia").text("status"), paid.obj("preShipmentMedia").text("count")))
        assertEquals(null, paid.obj("shipment").text("trackingCode"))
        assertEquals(listOf("سفارش ثبت شد", "پرداخت شد"), paid.objects("timeline").map { it.text("label") })

        clock.advance(Duration.ofHours(2))
        with(shippedOrders) { photographed(code) }
        val photoAt = clock.now.toString()
        val again = customer(1)
        val photographed = page(code, again)
        assertEquals("PHOTO_SENT", photographed.text("status"))
        assertEquals(listOf("DONE" to paidAt, "CURRENT" to photoAt, "UPCOMING" to null, "UPCOMING" to null), photographed.progress())
        // A change request after the photos (phase 3), approved or not.
        assertEquals(listOf("canRequestChange", "canApproveMedia"), photographed.actionsOn())
        assertEquals(listOf("SENT", "1", photoAt), listOf("status", "count", "sentAt").map { photographed.obj("preShipmentMedia").text(it) })
        assertEquals(1, photographed.obj("preShipmentMediaDetail").objects("items").size)
        assertEquals(clock.now.plus(Duration.ofHours(24)).toString(), photographed.obj("preShipmentMediaDetail").text("feedbackDeadline"))

        with(shippedOrders) { approved(code, again) }
        val approved = page(code, again)
        assertEquals("APPROVED", approved.obj("preShipmentMedia").text("status"))
        assertEquals(listOf("canRequestChange"), approved.actionsOn())
        assertEquals("عکس رو تأیید کردی", approved.objects("timeline").last().text("label"))

        clock.advance(Duration.ofHours(3))
        val ship = with(shippedOrders) { ship(code, """{"carrier":"POST","trackingCode":"PT-5000001"}""") }
        assertEquals(HttpStatusCode.OK, ship.status, ship.bodyAsText())
        val shipAt = clock.now.toString()
        val shipped = page(code, customer(1))
        assertEquals("SHIPPED", shipped.text("status"))
        assertEquals(listOf("DONE" to paidAt, "DONE" to photoAt, "CURRENT" to shipAt, "UPCOMING" to null), shipped.progress())
        val parcel = shipped.obj("shipment")
        assertEquals(listOf("PT-5000001", "https://tracking.post.ir/?id=PT-5000001", shipAt, null, null), listOf("trackingCode", "trackingUrl", "shippedAt", "deliveredAt", "waitingMessage").map { parcel.text(it) })
        assertEquals("تحویل ${parcel.text("carrierName")} شد", shipped.objects("timeline").last().text("label"))
        assertEquals(null, shipped.objects("items").single().text("returnableUntil"))

        clock.advance(Duration.ofDays(2))
        val delivered = client.post("$API_V1/admin/orders/${code.value}/status") {
            header(HttpHeaders.Authorization, shippedOrders.operator().bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DELIVERED"}""")
        }
        assertEquals(HttpStatusCode.OK, delivered.status, delivered.bodyAsText())
        val deliveredAt = clock.now.toString()
        val done = page(code, customer(1))
        assertEquals("DELIVERED", done.text("status"))
        assertEquals(listOf("DONE" to paidAt, "DONE" to photoAt, "DONE" to shipAt, "CURRENT" to deliveredAt), done.progress())
        assertEquals(deliveredAt, done.obj("shipment").text("deliveredAt"))
        assertEquals(clock.now.plus(Duration.ofDays(7)).toString(), done.objects("items").single().text("returnableUntil"))
        assertEquals(emptyList(), done.actionsOn())
        assertEquals(
            listOf("سفارش ثبت شد", "پرداخت شد", "عکس کیفت فرستاده شد", "عکس رو تأیید کردی", "تحویل ${parcel.text("carrierName")} شد", "تحویل شد"),
            done.objects("timeline").map { it.text("label") },
        )
        val card = client.get("$API_V1/me/orders") { header(HttpHeaders.Authorization, customer(1).bearer) }.json().objects("items").single()
        assertEquals(done.obj("progress"), card.obj("progress"))
        assertEquals(done.obj("shipment"), card.obj("shipment"))
    }

    @Test
    fun `totalSpent leaves out an order cancelled and given back in full`() = panel.app {
        val sara = customer(2)
        val kept = panel.paidOrders.paid(sara.user.id, nila[0])
        val dropped = panel.paidOrders.paid(sara.user.id, nila[1])
        val admin = panel.callerWith(Role.ADMIN)
        suspend fun customerRow() = client.get("$API_V1/admin/customers?q=${sara.user.phone.value}") { header(HttpHeaders.Authorization, admin.bearer) }
            .json().objects("items").single()
        suspend fun payableOf(code: OrderCode) = page(code, sara).obj("totals").text("payable")!!.toLong()
        val both = payableOf(kept) + payableOf(dropped)
        assertEquals(both, customerRow().text("totalSpent")!!.toLong())

        val cancelled = client.post("$API_V1/me/orders/${dropped.value}/cancel") {
            header(HttpHeaders.Authorization, sara.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"CHANGED_MIND"}""")
        }.json()
        assertEquals(both, customerRow().text("totalSpent")!!.toLong(), "nothing went back yet: the refund is PENDING")
        val refund = cancelled.obj("refund").text("id")!!
        val completed = client.patch("$API_V1/admin/refunds/$refund") {
            header(HttpHeaders.Authorization, admin.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"COMPLETED","referenceId":"99887766"}""")
        }
        assertEquals(HttpStatusCode.OK, completed.status, completed.bodyAsText())

        assertEquals(payableOf(kept), customerRow().text("totalSpent")!!.toLong())
        assertEquals(payableOf(dropped), page(dropped, sara).obj("totals").text("refunded")!!.toLong())
    }
}
