package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.common.valueobject.Money
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.seed.DevCatalogData
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /products` over the real stack on kiva_test: route, FindProductsUc and the read model of the prototype's 16
 * bags, seeded the way the dev seeder does it, plus one draft. Step 7 of the catalog plan is done when these pass.
 */
class WebV1ProductsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = Clock.fixed(Instant.parse("2026-10-07T08:00:00Z"), ZoneOffset.UTC)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()
    private val findProductsUc = FindProductsUc(
        ExposedTransactionManager(db), ProductQmRepositoryImpl(), CategoryRepositoryImpl(), ColorRepositoryImpl(), clock,
        Money.constructFromPersisted(3_000_000),
    )

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        seeded.deleteSeeded()
        others.deleteAll()
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing { route(API_V1) { webV1Products(findProductsUc) } }
        }
        block()
    }

    private suspend fun HttpResponse.body(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** The slugs of one page, every parameter given as a pair (encoded by the client, Arabic letters included). */
    private suspend fun ApplicationTestBuilder.slugs(vararg parameters: Pair<String, String>): List<String> {
        val response = client.get("$API_V1/products") { parameters.forEach { (name, value) -> parameter(name, value) } }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()["items"]!!.jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content }
    }

    private val soldOut = DevCatalogData.bags.filter { it.stockPerColor == 0 }.map { it.slug }.toSet()

    // ============ step 7 is done when ============

    @Test
    fun `with newest the sold-out bags come last, and with price_asc they don't`() = withApp {
        val newest = slugs("size" to "60")
        assertEquals(16, newest.size)
        assertEquals(setOf("shabtab-clutch", "aseman-tote-bag"), soldOut)
        assertEquals(soldOut, newest.takeLast(2).toSet())

        val cheapestFirst = slugs("sort" to "price_asc", "size" to "60")
        assertEquals(DevCatalogData.bags.sortedBy { it.price }.map { it.slug }, cheapestFirst)
        // Shabtab (980,000) is the third cheapest, sold out or not.
        assertEquals("shabtab-clutch", cheapestFirst[2])
    }

    @Test
    fun `q typed on an Arabic keyboard finds «کیف دوشی ماهک»`() = withApp {
        assertTrue("mahak-shoulder-bag" in slugs("q" to "كيف", "size" to "60"))
        assertEquals(listOf("mahak-shoulder-bag"), slugs("q" to "كيف دوشي ماهك"))
    }

    @Test
    fun `minPrice above maxPrice is a 400 VALIDATION_ERROR on maxPrice`() = withApp {
        val response = client.get("$API_V1/products?minPrice=900&maxPrice=100")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val problem = response.body()
        assertEquals("VALIDATION_ERROR", problem["code"]!!.jsonPrimitive.content)
        assertEquals("maxPrice", problem["errors"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a DRAFT product is never listed, however it is asked for`() = withApp {
        // Named like the seeded Mahak, so a search for it would find both if drafts leaked.
        val draft = others.newProduct(listOf(StoredCatalog.NewColor("lilac")), slug = "unpublished-bag")

        assertTrue("unpublished-bag" !in slugs("size" to "60"))
        assertEquals(listOf("mahak-shoulder-bag"), slugs("q" to "کیف دوشی ماهک"))
        assertEquals(emptyList(), slugs("ids" to "${draft.id.value}"))
    }

    // ============ and the rest of the contract ============

    @Test
    fun `filters together, paging and the card a shopper sees`() = withApp {
        // On sale, in pink or olive, from 1,000,000: Mahtab 1,150,000, Nila 1,450,000, Parnia 1,690,000, Barana 1,980,000.
        assertEquals(
            listOf("mahtab-clutch", "nila-crossbody-bag", "parnia-bucket-bag", "barana-backpack"),
            slugs("onSale" to "true", "color" to "pink,olive", "minPrice" to "1000000", "sort" to "price_asc"),
        )

        // The two cross-body bags, one a page.
        val firstOfTwo = client.get("$API_V1/products?category=cross&size=1").body()
        assertEquals(Json.parseToJsonElement("""{"page":1,"size":1,"totalItems":2,"totalPages":2,"hasNext":true}"""), firstOfTwo["meta"])

        // Of those, only Nila comes in pink. Its default is black, but the card shows the color asked for.
        val card = client.get("$API_V1/products?category=cross&color=pink").body()["items"]!!.jsonArray.single().jsonObject
        assertEquals(
            listOf("nila-crossbody-bag", "black", "pink", "IN_STOCK", "۱۷٪ تخفیف"),
            listOf(
                card["slug"]!!.jsonPrimitive.content,
                card["defaultColorKey"]!!.jsonPrimitive.content,
                card["displayColorKey"]!!.jsonPrimitive.content,
                card["stock"]!!.jsonObject["status"]!!.jsonPrimitive.content,
                card["badges"]!!.jsonArray.single { it.jsonObject["code"]!!.jsonPrimitive.content == "SALE" }.jsonObject["label"]!!.jsonPrimitive.content,
            ),
        )
    }
}
