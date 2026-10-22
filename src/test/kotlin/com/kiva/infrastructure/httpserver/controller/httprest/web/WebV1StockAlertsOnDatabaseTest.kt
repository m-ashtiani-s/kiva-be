package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.StockAlertTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «موجود شد خبرم کن» over kiva_test (roadmap step 10.1): subscribing (a login only, the rules in the contract's order,
 * the 30 limit and the hourly cap), «اشتراک‌های من», calling one off, and the product page's flags.
 */
class WebV1StockAlertsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val catalog = StoredCatalog()

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        catalog.deleteAll()
        seeded.deleteSeeded()
    }

    /** A product in the shop with these colors, each with [stock] bags (by key; missing: none). */
    private fun published(colors: List<String>, stock: Map<String, Int> = emptyMap()): Product {
        val product = catalog.newProduct(colors.map { NewColor(it) }, at = clock.now)
        catalog.change(product.id) { it.publish(catalog.staff, clock.now) }
        product.variants.forEach { variant ->
            val bags = stock[variant.colorKey.value] ?: 0
            if (bags > 0) catalog.stock(variant.id) { it.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, catalog.staff, clock.now) }
        }
        return product
    }

    private fun Product.variant(key: String): Long = variants.single { it.colorKey.value == key }.id.value

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.subscribe(product: Product, who: PanelTestStack.Caller?, variantId: Long? = null): HttpResponse =
        client.post("$API_V1/products/${product.id.value}/stock-alerts") {
            who?.let { header(HttpHeaders.Authorization, it.bearer) }
            contentType(ContentType.Application.Json)
            setBody(if (variantId == null) "{}" else """{"variantId":$variantId}""")
        }

    private suspend fun ApplicationTestBuilder.mineList(who: PanelTestStack.Caller): List<JsonObject> =
        Json.parseToJsonElement(client.get("$API_V1/me/stock-alerts") { header(HttpHeaders.Authorization, who.bearer) }.bodyAsText()).jsonArray.map { it.jsonObject }

    private fun rows(n: Int, who: PanelTestStack.Caller, status: StockAlertStatus, createdOn: Instant) = transaction(db) {
        StockAlertTbl.batchInsert((1..n).toList()) { i ->
            this[StockAlertTbl.alertId] = 9_000_000L + who.user.id.value * 100 + i
            this[StockAlertTbl.userId] = who.user.id.value
            this[StockAlertTbl.productId] = 8_000_000L + i
            this[StockAlertTbl.variantId] = null
            this[StockAlertTbl.status] = status
            this[StockAlertTbl.createdOn] = createdOn.atOffset(ZoneOffset.UTC)
            if (status == StockAlertStatus.CANCELLED) this[StockAlertTbl.cancelledOn] = createdOn.atOffset(ZoneOffset.UTC)
        }
    }

    @Test
    fun `a sold-out color - 201 with the account's phone masked - again 409 - a color in stock 422 - any color while one is in stock 422 with its key`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(901))
        val bag = published(listOf("lilac", "black"), stock = mapOf("black" to 2))

        val made = subscribe(bag, sara, bag.variant("lilac"))

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        val alert = made.json()
        assertEquals(ContractSchemas.propertiesOf("StockAlert"), alert.keys)
        assertEquals(listOf("ACTIVE", "SMS", sara.user.phone.masked(), bag.variant("lilac").toString(), "lilac", "هر وقت موجود شد، بهت پیامک می‌دیم"),
            listOf(alert.text("status"), alert.text("channel"), alert.text("phoneMasked"), alert.text("variantId"), alert.getValue("color").jsonObject.text("key"), alert.text("message")))
        assertEquals(HttpStatusCode.Conflict, subscribe(bag, sara, bag.variant("lilac")).status)
        val inStock = subscribe(bag, sara, bag.variant("black"))
        assertEquals(listOf("PRODUCT_IN_STOCK"), listOf(inStock.json().text("code")))
        val any = subscribe(bag, sara).json()
        assertEquals("PRODUCT_IN_STOCK", any.text("code"))
        assertEquals(listOf("black"), any.getValue("meta").jsonObject.getValue("inStockColorKeys").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(HttpStatusCode.Unauthorized, subscribe(bag, null, bag.variant("lilac")).status)
    }

    @Test
    fun `another product's color 400 VARIANT_NOT_FOUND - a draft 404 - any color of a sold-out bag beside its colors - after a cancel a new one`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(901))
        val soldOut = published(listOf("lilac", "black"))
        val other = published(listOf("olive"))
        val draft = catalog.newProduct(listOf(NewColor("lilac")))

        val wrong = subscribe(soldOut, sara, other.variant("olive")).json()
        assertEquals(listOf("variantId", "VARIANT_NOT_FOUND"), wrong.getValue("errors").jsonArray.single().jsonObject.let { listOf(it.text("field"), it.text("code")) })
        assertEquals(HttpStatusCode.NotFound, subscribe(draft, sara).status)
        assertEquals(HttpStatusCode.Created, subscribe(soldOut, sara, soldOut.variant("lilac")).status)
        val anyColor = subscribe(soldOut, sara)
        assertEquals(HttpStatusCode.Created, anyColor.status, "«any color» beside one color")
        val id = anyColor.json().text("id")

        assertEquals(HttpStatusCode.NoContent, client.delete("$API_V1/me/stock-alerts/$id") { header(HttpHeaders.Authorization, sara.bearer) }.status)
        assertEquals(HttpStatusCode.Created, subscribe(soldOut, sara).status, "after CANCELLED a new one")
    }

    @Test
    fun `the 31st active one 422 STOCK_ALERT_LIMIT_REACHED - the 11th made in an hour 429 with Retry-After`() = panel.app {
        val bag = published(listOf("lilac"))
        val full = panel.caller(PanelTestStack.phone(901))
        rows(30, full, StockAlertStatus.ACTIVE, clock.now.minus(Duration.ofHours(2)))
        val limited = subscribe(bag, full)
        assertEquals(HttpStatusCode.UnprocessableEntity, limited.status)
        assertEquals(listOf("STOCK_ALERT_LIMIT_REACHED", "30"), limited.json().let { listOf(it.text("code"), it.getValue("meta").jsonObject.text("limit")) })

        val busy = panel.caller(PanelTestStack.phone(902))
        rows(10, busy, StockAlertStatus.CANCELLED, clock.now.minus(Duration.ofMinutes(20)))
        val tooMany = subscribe(bag, busy)
        assertEquals(HttpStatusCode.TooManyRequests, tooMany.status)
        assertEquals((40 * 60).toString(), tooMany.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `«اشتراک‌های من» - active and recently told, newest first, no cancelled - a cancel twice 204 - someone else's 404`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(901))
        val first = published(listOf("lilac"))
        val second = published(listOf("black"))
        val a = subscribe(first, sara).json().text("id")!!
        clock.advance(Duration.ofMinutes(1))
        val b = subscribe(second, sara, second.variant("black")).json().text("id")!!

        assertEquals(listOf(b, a), mineList(sara).map { it.text("id") })
        val cancel = { id: String -> runBlocking { client.delete("$API_V1/me/stock-alerts/$id") { header(HttpHeaders.Authorization, sara.bearer) }.status } }
        assertEquals(listOf(HttpStatusCode.NoContent, HttpStatusCode.NoContent), listOf(cancel(a), cancel(a)))
        assertEquals(listOf(b), mineList(sara).map { it.text("id") })
        assertEquals(StockAlertStatus.CANCELLED, transaction(db) { StockAlertTbl.selectAll().where { StockAlertTbl.alertId eq a.toLong() }.single()[StockAlertTbl.status] })

        val other = panel.caller(PanelTestStack.phone(902))
        assertEquals(HttpStatusCode.NotFound, client.delete("$API_V1/me/stock-alerts/$b") { header(HttpHeaders.Authorization, other.bearer) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("$API_V1/me/stock-alerts/abc") { header(HttpHeaders.Authorization, other.bearer) }.status)
        assertTrue(mineList(other).isEmpty())
    }

    @Test
    fun `the product page - stockAlert for any color and stockAlertSubscribed on each color - a guest never subscribed`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(901))
        val bag = published(listOf("lilac", "black"))
        val anyId = subscribe(bag, sara).json().text("id")
        subscribe(bag, sara, bag.variant("black"))

        val page = client.get("$API_V1/products/${bag.id.value}") { header(HttpHeaders.Authorization, sara.bearer) }.json()
        assertEquals(listOf("true", anyId), page.getValue("stockAlert").jsonObject.let { listOf(it.text("subscribed"), it.text("alertId")) })
        assertEquals<Map<String?, String?>>(mapOf("lilac" to "false", "black" to "true"),
            page.getValue("variants").jsonArray.associate { it.jsonObject.getValue("color").jsonObject.text("key") to it.jsonObject.text("stockAlertSubscribed") })

        val guest = client.get("$API_V1/products/${bag.id.value}").json()
        assertEquals(listOf("false", null), guest.getValue("stockAlert").jsonObject.let { listOf(it.text("subscribed"), it.text("alertId")) })
        assertEquals(setOf<String?>("false"), guest.getValue("variants").jsonArray.map { it.jsonObject.text("stockAlertSubscribed") }.toSet())
    }
}
