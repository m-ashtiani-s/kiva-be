package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.reservation.usecase.command.ReleaseReservationGroupUc
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tracking (phase 2 step 2.9) over the real stack on kiva_test: a guest's `POST /tracking/lookup`, the public daily list
 * (`GET /tracking/daily/days`, `GET /tracking/daily`) and «رهگیری‌های من» (`GET /me/tracking`).
 */
class WebV1TrackingOnDatabaseTest {

    // Monday 2026-10-12, 11:30 in Tehran.
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val shippedOrders = ShippedOrders(panel)
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

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(800 + n))

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.lookup(orderCode: String, phone: String, forwardedFor: String? = null): HttpResponse =
        client.post("$API_V1/tracking/lookup") {
            contentType(ContentType.Application.Json)
            forwardedFor?.let { header(HttpHeaders.XForwardedFor, it) }
            setBody("""{"orderCode":"$orderCode","phone":"$phone"}""")
        }

    private suspend fun ApplicationTestBuilder.shipped(code: OrderCode, who: PanelTestStack.Caller, trackingCode: String, showInDailyList: Boolean = true) =
        with(shippedOrders) { shipped(code, who, trackingCode, showInDailyList = showInDailyList) }

    private fun groupOf(code: OrderCode): ReservationGroupId = transaction(db) {
        ReservationGroupId.constructFromPersisted(OrderQmTbl.select(OrderQmTbl.reservationGroupId).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.reservationGroupId]!!)
    }

    /** A problem body without its traceId (each request has its own). */
    private suspend fun HttpResponse.withoutTrace(): Map<String, Any?> = json().filterKeys { it != "traceId" }

    @Test
    fun `a guest follows a paid order with the recipient's or the buyer's phone - no address, prices or names, and anything else is the same 404`() = panel.app {
        val sara = customer(1)
        val code = panel.paidOrders.paid(sara.user.id, nila[0], to = PaidOrders.Destination.ISFAHAN)
        shipped(code, sara, "PT-1000001")
        val unpaid = panel.paidOrders.place(sara.user.id, nila[1]).first

        val byRecipient = lookup(code.value, PaidOrders.Destination.ISFAHAN.recipientPhone)

        assertEquals(HttpStatusCode.OK, byRecipient.status, byRecipient.bodyAsText())
        val raw = byRecipient.bodyAsText()
        val body = byRecipient.json()
        assertEquals(code.value to "SHIPPED", body.text("code") to body.text("status"))
        assertEquals("PT-1000001", body["shipment"]!!.jsonObject.text("trackingCode"))
        assertEquals(1, body["preShipmentMedia"]!!.jsonObject["totalCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, body["preShipmentMedia"]!!.jsonObject["preview"]!!.jsonArray.size)
        assertNotNull(body.text("loginHint"))
        listOf("address", "payable", "totals", "items", "recipient").forEach { assertFalse("\"$it\"" in raw, "$it in $raw") }
        listOf("رضا", "کریمی", "خیابان ولیعصر", PaidOrders.Destination.ISFAHAN.recipientPhone, sara.user.phone.value).forEach { assertFalse(it in raw, "$it in $raw") }

        assertEquals(HttpStatusCode.OK, lookup(code.value, sara.user.phone.value).status, "the buyer's phone")
        assertEquals(HttpStatusCode.OK, lookup(code.value.removePrefix("KV-"), sara.user.phone.value).status, "digits only")
        val persian = code.value.lowercase().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")
        assertEquals(HttpStatusCode.OK, lookup(persian, sara.user.phone.value).status, "kv- and Persian digits")

        val otherPhone = lookup(code.value, "09350000000")
        val notPaid = lookup(unpaid.value, sara.user.phone.value)
        val malformed = lookup("KV-12", sara.user.phone.value)
        assertEquals(listOf(HttpStatusCode.NotFound, HttpStatusCode.NotFound, HttpStatusCode.NotFound), listOf(otherPhone.status, notPaid.status, malformed.status))
        assertEquals("TRACKING_NOT_FOUND", otherPhone.json().text("code"))
        assertEquals(otherPhone.withoutTrace(), notPaid.withoutTrace())
        assertEquals(otherPhone.withoutTrace(), malformed.withoutTrace())
        val badPhone = lookup(code.value, "12345").json()
        assertEquals("phone" to "PHONE_INVALID", badPhone["errors"]!!.jsonArray.single().jsonObject.let { it.text("field") to it.text("code") })
    }

    @Test
    fun `the eleventh lookup in 10 minutes from one address is 429 with Retry-After - another address is still let through`() = panel.app(trustedProxyHops = 1) {
        repeat(10) { assertEquals(HttpStatusCode.NotFound, lookup("KV-999999", "09121234567", forwardedFor = "5.6.7.8").status, "request ${it + 1}") }

        val eleventh = lookup("KV-999999", "09121234567", forwardedFor = "5.6.7.8")

        assertEquals(HttpStatusCode.TooManyRequests, eleventh.status)
        val wait = assertNotNull(eleventh.headers[HttpHeaders.RetryAfter]).toLong()
        assertTrue(wait > 0)
        assertEquals(wait, eleventh.json()["meta"]!!.jsonObject["retryAfterSeconds"]!!.jsonPrimitive.content.toLong())
        assertEquals(HttpStatusCode.NotFound, lookup("KV-999999", "09121234567", forwardedFor = "9.9.9.9").status)
    }

    @Test
    fun `the daily list - masked only, a group one row, not what isn't listed, q by phone, letter and city, and the tabs`() = panel.app {
        val sara = customer(2)
        val home = panel.paidOrders.savedAddress(sara.user.id)
        val held = panel.paidOrders.paid(sara.user.id, nila[0], reserve = true, addressId = home)
        val joined = listOf(panel.paidOrders.paid(sara.user.id, nila[1], addressId = home), panel.paidOrders.paid(sara.user.id, nila[2], addressId = home))
        clock.advance(Duration.ofDays(5))
        runBlocking { panel.purchase.releaseReservationGroup.execute(ReleaseReservationGroupUc.Cmd(groupOf(held))) }
        // Saturday 2026-10-17 in Tehran from here on: yesterday was a Friday.
        clock.advance(Duration.ofMinutes(10))
        val again = customer(2)
        with(shippedOrders) {
            (listOf(held) + joined).forEach {
                photographed(it)
                approved(it, again)
            }
            assertEquals(HttpStatusCode.OK, ship(held, """{"carrier":"POST","trackingCode":"PT-2000001"}""").status)
        }
        val reza = customer(3)
        shipped(panel.paidOrders.paid(reza.user.id, nila[3], to = PaidOrders.Destination.ISFAHAN), reza, "PT-2000002")
        shipped(panel.paidOrders.paid(reza.user.id, nila[4], to = PaidOrders.Destination.ISFAHAN), reza, "PT-2000003", showInDailyList = false)
        val today = clock.now.atZone(java.time.ZoneId.of("Asia/Tehran")).toLocalDate()

        val list = client.get("$API_V1/tracking/daily?date=$today")

        assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
        val raw = list.bodyAsText()
        listOf("سارا", "محمدی", "رضا", "کریمی", PaidOrders.Destination.TEHRAN.recipientPhone, PaidOrders.Destination.ISFAHAN.recipientPhone).forEach {
            assertFalse(it in raw, "$it in $raw")
        }
        val items = list.json()["items"]!!.jsonArray
        assertEquals(listOf("PT-2000002", "PT-2000001"), items.map { it.jsonObject.text("trackingCode") }, "one row a parcel, latest first; not the unlisted")
        val tehran = items.last().jsonObject
        assertEquals(listOf("س*** م***", "س", "6789", "09** *** 6789"), listOf("recipientMasked", "recipientInitial", "phoneLast4", "phoneMasked").map { tehran.text(it) })
        assertEquals("https://tracking.post.ir/?id=PT-2000001", tehran.text("trackingUrl"))
        assertEquals(2L, list.json()["meta"]!!.jsonObject["totalItems"]!!.jsonPrimitive.content.toLong())

        suspend fun codesFor(q: String) = client.get("$API_V1/tracking/daily?date=$today&q=$q").json()["items"]!!.jsonArray.map { it.jsonObject.text("trackingCode") }
        assertEquals(listOf("PT-2000002"), codesFor("7890"))
        assertEquals(listOf("PT-2000001"), codesFor("س"))
        assertEquals(listOf("PT-2000002"), codesFor("اصفهان"))
        assertEquals(emptyList(), codesFor("1111"))

        val tabs = client.get("$API_V1/tracking/daily/days?days=7").bodyAsText().let { Json.parseToJsonElement(it) as JsonArray }
        assertEquals(7, tabs.size)
        assertEquals(listOf("امروز", "دیروز"), tabs.take(2).map { it.jsonObject.text("label") })
        assertEquals(today.toString() to "2", tabs.first().jsonObject.let { it.text("date") to it.text("shipmentCount") })
        val friday = tabs.map { it.jsonObject }.single { it.text("date") == today.minusDays(1).toString() }
        assertEquals("true", friday.text("isNonShippingDay"))
        val fridayList = client.get("$API_V1/tracking/daily?date=${today.minusDays(1)}").json()
        assertEquals("جمعه‌ها ارسال نداریم", fridayList.text("emptyMessage"))
        assertEquals(null, list.json().text("emptyMessage"))

        assertEquals("days" to "DAYS_INVALID", client.get("$API_V1/tracking/daily/days?days=31").json()["errors"]!!.jsonArray.single().jsonObject.let { it.text("field") to it.text("code") })
        assertEquals("date" to "DATE_INVALID", client.get("$API_V1/tracking/daily?date=dirooz").json()["errors"]!!.jsonArray.single().jsonObject.let { it.text("field") to it.text("code") })
        assertEquals(0, client.get("$API_V1/tracking/daily?date=${today.minusDays(40)}").json()["items"]!!.jsonArray.size, "older than 30 days")
        assertEquals(0, client.get("$API_V1/tracking/daily?date=${today.plusDays(1)}").json()["items"]!!.jsonArray.size, "the future")
    }

    @Test
    fun `a parcel handed over at a quarter to nine UTC is on the next day's list - Tehran's day, not UTC's`() = panel.app {
        val sara = customer(6)
        val code = panel.paidOrders.paid(sara.user.id, nila[5])
        with(shippedOrders) {
            photographed(code)
            approved(code, sara)
        }
        clock.advance(Duration.between(clock.now, Instant.parse("2026-10-12T20:45:00Z")))
        val shipped = with(shippedOrders) { ship(code, """{"carrier":"POST","trackingCode":"PT-4000001"}""") }
        assertEquals(HttpStatusCode.OK, shipped.status, shipped.bodyAsText())

        suspend fun codesOn(date: String) = client.get("$API_V1/tracking/daily?date=$date").json()["items"]!!.jsonArray.map { it.jsonObject.text("trackingCode") }
        assertEquals(listOf("PT-4000001"), codesOn("2026-10-13"))
        assertEquals(emptyList(), codesOn("2026-10-12"))
        val tabs = Json.parseToJsonElement(client.get("$API_V1/tracking/daily/days?days=2").bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(listOf("2026-10-13" to "1", "2026-10-12" to "0"), tabs.map { it.text("date") to it.text("shipmentCount") })
        val mine = Json.parseToJsonElement(client.get("$API_V1/me/tracking") { header(HttpHeaders.Authorization, customer(6).bearer) }.bodyAsText()).jsonArray
        assertEquals("2026-10-13", mine.single().jsonObject.text("date"))
    }

    @Test
    fun `«رهگیری‌های من» - only the customer's own, by the day they left, the latest day first`() = panel.app {
        val sara = customer(4)
        val first = panel.paidOrders.paid(sara.user.id, nila[0])
        shipped(first, sara, "PT-3000001")
        shipped(panel.paidOrders.paid(customer(5).user.id, nila[1]), customer(5), "PT-3000002")
        clock.advance(Duration.ofDays(1))
        val again = customer(4)
        val second = panel.paidOrders.paid(sara.user.id, nila[2])
        shipped(second, again, "PT-3000003")
        panel.paidOrders.paid(sara.user.id, nila[3])

        val mine = client.get("$API_V1/me/tracking") { header(HttpHeaders.Authorization, again.bearer) }

        assertEquals(HttpStatusCode.OK, mine.status, mine.bodyAsText())
        val days = Json.parseToJsonElement(mine.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(listOf("2026-10-13", "2026-10-12"), days.map { it.text("date") })
        assertEquals(listOf(listOf(second.value), listOf(first.value)), days.map { day -> day["shipments"]!!.jsonArray.map { it.jsonObject.text("orderCode") } })
        val parcel = days.first()["shipments"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("POST", "PT-3000003", "https://tracking.post.ir/?id=PT-3000003"), listOf("carrier", "trackingCode", "trackingUrl").map { parcel.text(it) })
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1/me/tracking").status)
    }
}
