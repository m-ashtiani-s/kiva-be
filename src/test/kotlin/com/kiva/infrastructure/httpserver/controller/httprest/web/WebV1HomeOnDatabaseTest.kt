package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.content.usecase.query.GetHomePageUc
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The home page over kiva_test (roadmap step 11.6): every part in the contract's shape, an empty shop answered with
 * nothing rather than an error, no heart for anyone, ETag and 304, and a fixed number of reads.
 */
class WebV1HomeOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val catalog = StoredCatalog()

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        catalog.deleteAll()
        seeded.deleteSeeded()
    }

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `every part in the contract's shape - 8 newest, the showcase, the menu, the texts with the settings filled in`() = panel.app {
        runBlocking { seeded.seeder.seed() }

        val response = client.get("$API_V1/home")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val home = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(ContractSchemas.propertiesOf("HomePage"), home.keys)
        val hero = home.getValue("hero").jsonObject
        assertEquals(setOf("eyebrow", "title", "text", "primaryCta", "secondaryCta", "showcaseProduct", "showcaseMessenger"), hero.keys)
        assertEquals(ContractSchemas.propertiesOf("ProductSummary"), hero.getValue("showcaseProduct").jsonObject.keys)
        assertEquals(8, home.getValue("newArrivals").jsonArray.size)
        assertEquals(hero.getValue("showcaseProduct").jsonObject.text("id"), home.getValue("newArrivals").jsonArray.first().jsonObject.text("id"), "no choice: the newest")
        assertTrue(home.getValue("categories").jsonArray.isNotEmpty())
        assertEquals(3, home.getValue("howItWorks").jsonArray.size)
        val features = home.getValue("features").jsonArray.map { it.jsonObject.text("title") }
        assertTrue(features.none { it!!.contains("{") }, "every {setting} filled: $features")
        assertEquals(listOf(JsonNull), listOf(home["sale"]))
        assertEquals(ContractSchemas.propertiesOf("TestimonialListResponse"), home.getValue("testimonials").jsonObject.keys)
        assertEquals(response.headers[HttpHeaders.ETag]?.isNotBlank(), true)
    }

    @Test
    fun `an empty shop - no showcase and empty lists, not an error`() = panel.app {
        val home = Json.parseToJsonElement(client.get("$API_V1/home").bodyAsText()).jsonObject
        assertEquals(JsonNull, home.getValue("hero").jsonObject["showcaseProduct"])
        assertEquals(listOf(0, 0), listOf(home.getValue("newArrivals").jsonArray.size, home.getValue("promos").jsonArray.size))
    }

    @Test
    fun `a logged-in customer with a heart still gets no heart - the same ETag answers 304`() = panel.app {
        runBlocking { seeded.seeder.seed() }
        val sara = panel.caller(PanelTestStack.phone(901))
        val newest = Json.parseToJsonElement(client.get("$API_V1/home").bodyAsText()).jsonObject.getValue("newArrivals").jsonArray.first().jsonObject.text("id")
        val hearted = client.put("$API_V1/me/wishlist/items/$newest") { header(HttpHeaders.Authorization, sara.bearer) }
        assertEquals(HttpStatusCode.OK, hearted.status, hearted.bodyAsText())

        val mine = client.get("$API_V1/home") { header(HttpHeaders.Authorization, sara.bearer) }
        val cards = Json.parseToJsonElement(mine.bodyAsText()).jsonObject.getValue("newArrivals").jsonArray.map { it.jsonObject }
        assertEquals(setOf<String?>(null), cards.map { it.text("isWishlisted") }.toSet())

        val again = client.get("$API_V1/home") { header(HttpHeaders.IfNoneMatch, mine.headers[HttpHeaders.ETag]!!) }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    @Test
    fun `the home page reads as many statements for 30 products as for 3`() {
        fun statements(): Int = transaction(db) {
            val before = statementCount
            runBlocking { panel.home.home.execute(GetHomePageUc.Qry) }
            statementCount - before
        }
        repeat(3) { bag() }
        val few = statements()
        repeat(27) { bag() }
        assertEquals(few, statements())
        assertTrue(few > 0, "the reads were counted in this transaction")
    }

    private fun bag() {
        val product = catalog.newProduct(listOf(NewColor("lilac"), NewColor("black")), at = clock.now)
        catalog.change(product.id) { it.publish(catalog.staff, clock.now) }
        catalog.stock(product.variants.first().id) { it.adjust(StockDelta.constructFromPersisted(2), AdjustmentReason.RESTOCK, null, catalog.staff, clock.now) }
    }
}
