package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.persistence.exposed.tbl.ColorTbl
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live search over the seeded catalog on kiva_test (roadmap step 12.2): the roadmap's example exactly, the ranking,
 * the query's rules, drafts never shown, and the same count as `GET /products?q=`.
 */
class WebV1SearchOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
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
        transaction(TestDatabase.database) { ColorTbl.deleteWhere { ColorTbl.colorKey like "tst%" } }
    }

    @Test
    fun `the hints - menu categories with products, colours a product in the shop comes in, the typed queries - cached with 304`() = panel.app {
        val admin = panel.callerWith(Role.ADMIN)
        val made = client.post("$API_V1/admin/colors") {
            header(HttpHeaders.Authorization, admin.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"key":"tst-draft-only","name":"فقط پیش‌نویس","hex":"#123456","sortOrder":0}""")
        }
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        catalog.newProduct(listOf(NewColor("tst-draft-only")))

        val response = client.get("$API_V1/search/hints")
        assertEquals(HttpStatusCode.OK, response.status)
        val hints = response.json()
        assertEquals(ContractSchemas.propertiesOf("SearchHints"), hints.keys)
        assertEquals(listOf("کیف مجلسی", "کوله دانشگاه", "کراس‌بادی مشکی"), hints.getValue("popularQueries").jsonArray.map { it.jsonPrimitive.content })
        val colors = hints.getValue("colors").jsonArray.map { it.jsonObject.text("key") }
        assertTrue("black" in colors && "tst-draft-only" !in colors, "$colors")
        assertTrue(hints.getValue("categories").jsonArray.size in 1..8)

        val again = client.get("$API_V1/search/hints") { header(HttpHeaders.IfNoneMatch, response.headers[HttpHeaders.ETag]!!) }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    private suspend fun ApplicationTestBuilder.suggest(q: String, limit: String? = null): HttpResponse =
        client.get("$API_V1/search/suggest") {
            parameter("q", q)
            limit?.let { parameter("limit", it) }
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `the roadmap's example - an Arabic yeh, black matched, the crossbody's name marked before its half-space and its link in black`() = panel.app {
        val response = suggest("کراس مشکي")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals(ContractSchemas.propertiesOf("SearchSuggestResponse"), body.keys)
        assertEquals(listOf("کراس مشکي", "کراس مشکی", "/products?q=%DA%A9%D8%B1%D8%A7%D8%B3%20%D9%85%D8%B4%DA%A9%DB%8C"), listOf(body.text("query"), body.text("normalizedQuery"), body.text("seeAllUrl")))
        val nila = body.getValue("products").jsonArray.map { it.jsonObject }.single { it.text("slug") == "nila-crossbody-bag" }
        assertEquals(ContractSchemas.propertiesOf("SearchSuggestProduct"), nila.keys)
        assertEquals(
            listOf("<mark>کراس</mark>‌بادی نیلا", "black", "/product/nila-crossbody-bag?color=black", "کراس‌بادی"),
            listOf(nila.text("nameHighlighted"), nila.text("matchedColorKey"), nila.text("url"), nila.text("categoryName")),
        )
        assertEquals(listOf("black"), body.getValue("colors").jsonArray.map { it.jsonObject.text("key") })
    }

    @Test
    fun `a product whose name has the word comes before one where only its colour or category has it - a sold-out one after those in stock`() = panel.app {
        // «مجلسی»: in the name of «کیف مجلسی شب‌تاب», only in the category («کیف مجلسی») of «کلاچ مهتاب», which sells more.
        transaction(TestDatabase.database) {
            ProductQmTbl.update({ ProductQmTbl.slug eq "mahtab-clutch" }) { it[soldCount] = 999 }
        }
        val party = suggest("مجلسی").json().getValue("products").jsonArray.map { it.jsonObject.text("slug") }
        assertEquals(listOf("shabtab-clutch", "mahtab-clutch"), party)

        val products = suggest("کراس", limit = "20").json().getValue("products").jsonArray.map { it.jsonObject }

        val nila = products.first { it.text("slug") == "nila-crossbody-bag" }.text("id")!!.toLong()
        val delaram = products.first { it.text("slug") == "delaram-crossbody-bag" }.text("id")!!.toLong()
        // Nila sells more, so only «in stock first» can put Delaram before it.
        transaction(TestDatabase.database) { ProductQmTbl.update({ ProductQmTbl.slug eq "nila-crossbody-bag" }) { it[soldCount] = 999 } }
        val nilaColors = listOf("black", "olive", "red", "cream", "gray", "pink", "blue").map { panel.purchase.variantOf("nila-crossbody-bag", it) }
        nilaColors.forEach { variant -> catalog.stock(VariantId.constructFromPersisted(variant)) { it.adjust(StockDelta.constructFromPersisted(-panel.purchase.availableOf(variant)), AdjustmentReason.DAMAGED, null, catalog.staff, clock.now) } }
        val after = suggest("کراس", limit = "20").json().getValue("products").jsonArray.map { it.jsonObject.text("id")!!.toLong() }
        assertTrue(after.indexOf(delaram) < after.indexOf(nila), "sold out after those in stock: $after")
    }

    @Test
    fun `a half-space and a space alone 400 SEARCH_QUERY_EMPTY - limit 21 400 - 101 letters 400`() = panel.app {
        assertEquals(listOf("q" to "SEARCH_QUERY_EMPTY"), suggest("‌ ").json().getValue("errors").jsonArray.map { it.jsonObject.text("field") to it.jsonObject.text("code") })
        assertEquals(HttpStatusCode.BadRequest, suggest("کیف", limit = "21").status)
        assertEquals(HttpStatusCode.BadRequest, suggest("ک".repeat(101)).status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1/search/suggest").status)
    }

    @Test
    fun `a draft is never found - totalProducts is GET products' count for the same words - both words must match`() = panel.app {
        val draft = catalog.newProduct(listOf(NewColor("lilac")))
        val draftName = Json.parseToJsonElement(client.get("$API_V1/products/${draft.id.value}").bodyAsText()).jsonObject.text("code")
        assertEquals("PRODUCT_NOT_FOUND", draftName)
        val any = suggest("کیف", limit = "20").json()
        assertTrue(any.getValue("products").jsonArray.none { it.jsonObject.text("id") == draft.id.value.toString() })

        listOf("کیف", "کیف مشکی", "کراس قرمز").forEach { q ->
            val total = suggest(q).json().text("totalProducts")
            val listed = Json.parseToJsonElement(client.get("$API_V1/products") { parameter("q", q); parameter("size", "60") }.bodyAsText()).jsonObject
            assertEquals(listed.getValue("meta").jsonObject.text("totalItems"), total, "for «$q»")
        }
        val both = Json.parseToJsonElement(client.get("$API_V1/products") { parameter("q", "کیف دوشی ماهک"); parameter("size", "60") }.bodyAsText()).jsonObject
        assertEquals(listOf("mahak-shoulder-bag"), both.getValue("items").jsonArray.map { it.jsonObject.text("slug") })
    }
}
