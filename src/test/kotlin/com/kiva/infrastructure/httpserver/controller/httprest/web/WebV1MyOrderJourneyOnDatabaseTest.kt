package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.UpdateVariantUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
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
import kotlinx.serialization.json.JsonPrimitive
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
        // Returnable for 7 days (phase 7), and its bag not reviewed yet (phase 8): the two buttons of a delivered order.
        assertEquals(listOf("canReturn", "canReview"), done.actionsOn())
        assertEquals(
            listOf("سفارش ثبت شد", "پرداخت شد", "عکس کیفت فرستاده شد", "عکس رو تأیید کردی", "تحویل ${parcel.text("carrierName")} شد", "تحویل شد"),
            done.objects("timeline").map { it.text("label") },
        )
        val card = client.get("$API_V1/me/orders") { header(HttpHeaders.Authorization, customer(1).bearer) }.json().objects("items").single()
        assertEquals(done.obj("progress"), card.obj("progress"))
        assertEquals(done.obj("shipment"), card.obj("shipment"))
    }

    /** Nila's olive gets a price of its own, as the panel's color form sets it. */
    private fun oliveAt(price: Long) {
        val staff = PanelActor(UserId.constructFromPersisted(panel.callerWith(Role.ADMIN).userId), null)
        val productId = transaction(TestDatabase.database) {
            ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq nila[1] }.single()[ProductVariantQmTbl.productId]
        }
        runBlocking { panel.adminProducts.updateVariant.execute(UpdateVariantUc.Cmd(productId.toString(), nila[1].toString(), ProductInput.Variant(colorKey = "olive", priceOverride = price), staff)) }
            .getOrNull() ?: error("not priced")
    }

    /** What the page's totals say, each by its name. */
    private fun JsonObject.totals(): Map<String, Long> =
        obj("totals").filterValues { it is JsonPrimitive && it !is JsonNull && !it.isString }.mapValues { it.value.jsonPrimitive.long }

    /** Section 8.2, row 7: the totals always add up - subtotal − codeDiscount + shippingCost = payable + balanceDue − change refunds. */
    private fun assertAddsUp(totals: Map<String, Long>, changeRefunds: Long, step: String) =
        assertEquals(
            totals.getValue("subtotal") - totals.getValue("codeDiscount") + totals.getValue("shippingCost"),
            totals.getValue("payable") + totals.getValue("balanceDue") - changeRefunds,
            "$step: $totals",
        )

    @Test
    fun `a dearer color after the photos - asked, applied, the balance paid at the gateway, new photos, approved, shipped - each step what the contract says`() = panel.app {
        oliveAt(1_650_000)
        val sara = customer(3)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        val placed = page(code, customer(3)).totals()
        assertAddsUp(placed, 0, "paid")
        with(shippedOrders) { photographed(code) }

        // The customer asks for the olive one.
        val asked = client.post("$API_V1/me/orders/${code.value}/media-feedback") {
            header(HttpHeaders.Authorization, customer(3).bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"REQUEST_CHANGE","changeType":"COLOR","orderItemId":1,"desiredVariantId":${nila[1]},"note":"زیتونی قشنگ‌تره"}""")
        }
        assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
        val requested = page(code, customer(3))
        assertEquals(listOf("CHANGE_REQUESTED", "CHANGE_REQUESTED"), listOf(requested.text("status"), requested.obj("preShipmentMedia").text("status")))
        assertEquals(emptyList(), requested.actionsOn())
        val feedback = requested.obj("preShipmentMediaDetail").obj("feedback")
        assertEquals(listOf("REQUEST_CHANGE", "COLOR", "1", nila[1].toString(), null), listOf("decision", "changeType", "orderItemId", "desiredVariantId", "resolution").map { feedback.text(it) })
        assertEquals("درخواست تغییر ثبت شد", requested.objects("timeline").last().text("label"))

        // The operator carries it out: 200,000 more is owed, and the photos wait for a new batch.
        clock.advance(Duration.ofHours(1))
        val applied = client.post("$API_V1/admin/orders/${code.value}/change-request/resolution") {
            header(HttpHeaders.Authorization, shippedOrders.operator().bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"APPLY","note":"زیتونی گذاشتیم"}""")
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals(listOf(sara.user.phone to code), panel.purchase.auth.sms.changeResolved.toList())
        val owing = page(code, customer(3))
        assertEquals("PROCESSING", owing.text("status"))
        assertEquals(listOf("canPay"), owing.actionsOn())
        assertEquals(listOf(nila[1].toString(), "1650000"), owing.objects("items").single().let { listOf(it.text("variantId"), it.text("unitPrice")) })
        val owed = owing.totals()
        assertEquals(listOf(200_000L, placed.getValue("payable"), 1_650_000L), listOf(owed.getValue("balanceDue"), owed.getValue("payable"), owed.getValue("subtotal")))
        assertAddsUp(owed, 0, "applied")
        assertEquals(listOf("WAITING", "1"), listOf(owing.obj("preShipmentMedia").text("status"), owing.obj("preShipmentMedia").text("count")), "the old batch stays until a new one")
        val resolution = owing.obj("preShipmentMediaDetail").obj("feedback").obj("resolution")
        assertEquals(listOf("APPLY", nila[1].toString(), "زیتونی گذاشتیم"), listOf(resolution.text("decision"), resolution.text("variantId"), resolution.text("note")))
        assertEquals(listOf("رنگ عوض شد", "زیتونی گذاشتیم"), owing.objects("timeline").last().let { listOf(it.text("label"), it.text("note")) })

        // The customer pays the balance at the pretend gateway.
        val started = client.post("$API_V1/me/orders/${code.value}/payments") {
            header(HttpHeaders.Authorization, customer(3).bearer)
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Created, started.status, started.bodyAsText())
        val attempt = started.json()
        assertEquals(listOf("200000", clock.now.plus(Duration.ofMinutes(20)).toString()), listOf(attempt.text("amount"), attempt.text("expiresAt")))
        panel.paidOrders.back(attempt.text("paymentId")!!)
        val result = client.get("$API_V1/payments/${attempt.text("paymentId")}") { header(HttpHeaders.Authorization, customer(3).bearer) }.json()
        assertEquals("SUCCEEDED", result.text("status"))
        val settled = page(code, customer(3))
        assertEquals("PROCESSING", settled.text("status"))
        assertEquals(emptyList(), settled.actionsOn())
        val paidUp = settled.totals()
        assertEquals(listOf(0L, placed.getValue("payable") + 200_000L), listOf(paidUp.getValue("balanceDue"), paidUp.getValue("payable")))
        assertAddsUp(paidUp, 0, "balance paid")
        assertEquals("مانده‌ی ۲۰۰٬۰۰۰ تومانی پرداخت شد", settled.objects("timeline").last().text("label"))

        // New photos, «همونه!», and it ships.
        clock.advance(Duration.ofHours(1))
        with(shippedOrders) { photographed(code) }
        with(shippedOrders) { approved(code, customer(3)) }
        clock.advance(Duration.ofHours(1))
        val ship = with(shippedOrders) { ship(code, """{"carrier":"POST","trackingCode":"PT-5000002"}""") }
        assertEquals(HttpStatusCode.OK, ship.status, ship.bodyAsText())
        val shipped = page(code, customer(3))
        assertEquals("SHIPPED", shipped.text("status"))
        assertAddsUp(shipped.totals(), 0, "shipped")
        assertEquals(
            listOf(
                "سفارش ثبت شد", "پرداخت شد", "عکس کیفت فرستاده شد", "درخواست تغییر ثبت شد", "رنگ عوض شد", "مانده‌ی ۲۰۰٬۰۰۰ تومانی پرداخت شد",
                "عکس کیفت فرستاده شد", "عکس رو تأیید کردی", "تحویل ${shipped.obj("shipment").text("carrierName")} شد",
            ),
            shipped.objects("timeline").map { it.text("label") },
        )
    }

    @Test
    fun `totalSpent leaves out an order cancelled and given back in full`() = panel.app {
        val sara = customer(2)
        val kept = panel.paidOrders.paid(sara.user.id, nila[0])
        val dropped = panel.paidOrders.paid(sara.user.id, nila[1])
        val admin = panel.callerWith(Role.ADMIN)
        suspend fun customerRow() = client.get("$API_V1/admin/customers?q=${sara.user.activePhone.value}") { header(HttpHeaders.Authorization, admin.bearer) }
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
