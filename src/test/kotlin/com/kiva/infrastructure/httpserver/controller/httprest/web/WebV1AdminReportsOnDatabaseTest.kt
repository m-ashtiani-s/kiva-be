package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import java.time.Instant
import kotlin.test.assertEquals

/**
 * `GET /admin/reports/overview` (step 4.4) on kiva_test, over an order paid through the shop's own use cases: the
 * response has the contract's fields, shares are written with one decimal, and the range's rules answer 400. The
 * numbers themselves are ReportOverviewOnDatabaseTest's. The clock is in 2032, where no other test writes.
 */
class WebV1AdminReportsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2032-06-10T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private suspend fun ApplicationTestBuilder.overview(query: String, caller: PanelTestStack.Caller = panel.callerWith(Role.ADMIN)): HttpResponse =
        client.get("$API_V1/admin/reports/overview$query") { header(HttpHeaders.Authorization, caller.bearer) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `ADMIN - every field of the contract, the shares with one decimal, the paid order counted, every key and day there`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(801))
        val nila = panel.purchase.variantOf("nila-crossbody-bag", "black")
        panel.paidOrders.paid(sara.user.id, nila)

        val response = overview("?from=2032-06-09&to=2032-06-10")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val report = response.json()
        assertEquals(ContractSchemas.propertiesOf("AdminReportOverview"), report.keys)
        assertEquals(listOf("2032-06-09", "2032-06-10", "1", "1"), listOf("from", "to", "ordersCount", "newCustomers").map { report.text(it) })
        assertEquals(report.text("revenue"), report.text("averageOrderValue"))
        // Written as the contract's numbers with one decimal, not as tenths.
        assertEquals(listOf("0.0", "0.0", "0.0", "0.0"), listOf("reservationShare", "mediaApprovalRate", "changeRequestRate", "returnRate").map { report.text(it) })
        assertEquals(setOf("TIPAX", "POST", "COURIER"), report.getValue("shippingMix").jsonObject.keys)
        assertEquals("1", report.getValue("shippingMix").jsonObject.text("POST"))
        assertEquals(setOf("RUBIKA", "TELEGRAM", "BALE"), report.getValue("messengerMix").jsonObject.keys)
        val top = report.getValue("topProducts").jsonArray.single().jsonObject
        assertEquals("1", top.text("unitsSold"))
        val product = top.getValue("product").jsonObject
        assertEquals(ContractSchemas.propertiesOf("ProductRef"), product.keys)
        assertEquals("nila-crossbody-bag", product.text("slug"))
        assertEquals(null, product.text("colorKey"))
        val days = report.getValue("dailySeries").jsonArray.map { it.jsonObject }
        assertEquals(listOf("2032-06-09 0", "2032-06-10 1"), days.map { "${it.text("date")} ${it.text("orders")}" })
        assertEquals(report.text("revenue"), days.last().text("revenue"))
    }

    @Test
    fun `from and to must come and be days - FIELD_REQUIRED and DATE_INVALID on the field`() = panel.app {
        assertEquals(listOf("from FIELD_REQUIRED", "to FIELD_REQUIRED"), overview("").fieldErrors())
        assertEquals(listOf("to DATE_INVALID"), overview("?from=2032-06-01&to=fardā").fieldErrors())
    }

    @Test
    fun `from after to, and a range of 400 days, are 400 on to`() = panel.app {
        val backwards = overview("?from=2032-06-10&to=2032-06-09")
        assertEquals(HttpStatusCode.BadRequest, backwards.status)
        assertEquals(listOf("to DATE_RANGE_INVALID"), backwards.fieldErrors())

        val tooLong = overview("?from=2031-05-07&to=2032-06-10")
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertEquals(listOf("to DATE_RANGE_TOO_LONG"), tooLong.fieldErrors())
        // A field's error carries no meta: the longest range is in its sentence.
        assertEquals("بازه حداکثر ۳۶۶ روزه", tooLong.json().getValue("errors").jsonArray.single().jsonObject.text("message"))
    }

    @Test
    fun `SUPPORT and OPERATOR are 403`() = panel.app {
        listOf(Role.SUPPORT, Role.OPERATOR).forEach { role ->
            assertEquals(HttpStatusCode.Forbidden, overview("?from=2032-06-09&to=2032-06-10", panel.callerWith(role)).status, "$role")
        }
    }
}
