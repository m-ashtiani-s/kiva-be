package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.common.valueobject.Money
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
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
import kotlinx.serialization.json.JsonNull
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
 * The products routes over the real stack on kiva_test: routes, use cases and the read model of the prototype's 16
 * bags, seeded the way the dev seeder does it, plus one draft. Steps 7, 8 and 9 of the catalog plan are done when
 * their checks here pass.
 */
class WebV1ProductsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = Clock.fixed(Instant.parse("2026-10-07T08:00:00Z"), ZoneOffset.UTC)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()
    private val findProductsUc = FindProductsUc(
        ExposedTransactionManager(db), ProductQmRepositoryImpl(), CategoryRepositoryImpl(), ColorRepositoryImpl(), MaterialRepositoryImpl(),
        clock, Money.constructFromPersisted(3_000_000),
    )
    private val getProductFacetsUc = GetProductFacetsUc(
        ExposedTransactionManager(db), ProductQmRepositoryImpl(), CategoryRepositoryImpl(), ColorRepositoryImpl(), MaterialRepositoryImpl(),
        clock,
    )
    private val findRelatedProductsUc = FindRelatedProductsUc(
        ExposedTransactionManager(db), ProductQmRepositoryImpl(), CategoryRepositoryImpl(), ColorRepositoryImpl(), clock,
        Money.constructFromPersisted(3_000_000),
    )
    private val getProductUc = GetProductUc(
        ExposedTransactionManager(db), ProductQmRepositoryImpl(), CategoryRepositoryImpl(), MaterialRepositoryImpl(), ColorRepositoryImpl(),
        clock, Money.constructFromPersisted(3_000_000),
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
            routing { route(API_V1) { webV1Products(findProductsUc, getProductFacetsUc, getProductUc, findRelatedProductsUc, siteUrl = "https://kiva.ir") } }
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

    // ============ step 8 is done when ============

    private suspend fun ApplicationTestBuilder.page(idOrSlug: String): HttpResponse = client.get("$API_V1/products/$idOrSlug")

    /** The product's number, as the list gives it: on kiva_test ids follow the sequence, not the prototype. */
    private suspend fun ApplicationTestBuilder.idOf(slug: String): Long =
        client.get("$API_V1/products?size=60").body()["items"]!!.jsonArray
            .single { it.jsonObject["slug"]!!.jsonPrimitive.content == slug }.jsonObject["id"]!!.jsonPrimitive.content.toLong()

    @Test
    fun `its number, its code KV-1001 and its slug all open the same product`() = withApp {
        val id = idOf("mahak-shoulder-bag")

        val pages = listOf("$id", "KV-1001", "mahak-shoulder-bag").map { page(it) }

        assertEquals(List(3) { HttpStatusCode.OK }, pages.map { it.status })
        val bodies = pages.map { it.body() }
        assertEquals(List(3) { id }, bodies.map { it["id"]!!.jsonPrimitive.content.toLong() })
        // However it was asked for, the canonical address is the slug's.
        assertEquals(List(3) { "https://kiva.ir/product/mahak-shoulder-bag" }, bodies.map { it["seo"]!!.jsonObject["canonicalUrl"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a DRAFT product, or one that doesn't exist, is 404 PRODUCT_NOT_FOUND`() = withApp {
        val draft = others.newProduct(listOf(StoredCatalog.NewColor("lilac")), slug = "unpublished-bag")

        val answers = listOf("${draft.id.value}", "unpublished-bag", draft.sku.value, "999999", "KV-9999", "no-such-bag", "kv-1001").map { page(it) }

        assertEquals(List(7) { HttpStatusCode.NotFound }, answers.map { it.status })
        assertEquals(List(7) { "PRODUCT_NOT_FOUND" }, answers.map { it.body()["code"]!!.jsonPrimitive.content })
    }

    @Test
    fun `the stock number is given only where the status is LOW_STOCK, null elsewhere`() = withApp {
        // Plenty (Mahak, 12 a color), few of each color (Aida 3, Hasti 2) and none (Shabtab).
        val stocks = listOf("mahak-shoulder-bag", "aida-handbag", "hasti-shoulder-bag", "shabtab-clutch").flatMap { slug ->
            val body = page(slug).body()
            listOf(body["stock"]!!.jsonObject) + body["variants"]!!.jsonArray.map { it.jsonObject["stock"]!!.jsonObject }
        }

        stocks.forEach { stock ->
            val lowStock = stock["status"]!!.jsonPrimitive.content == "LOW_STOCK"
            assertEquals(lowStock, stock.getValue("availableQuantity") != JsonNull, "$stock")
        }
        assertEquals(setOf("IN_STOCK", "LOW_STOCK", "OUT_OF_STOCK"), stocks.map { it["status"]!!.jsonPrimitive.content }.toSet())
        // Aida has 3 of each color: each color says so, the bag as a whole (12) doesn't.
        val aida = page("aida-handbag").body()
        assertEquals("IN_STOCK", aida["stock"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(List(4) { "3" }, aida["variants"]!!.jsonArray.map { it.jsonObject["stock"]!!.jsonObject["availableQuantity"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a sold-out bag keeps its page, and says so`() = withApp {
        val shabtab = page("shabtab-clutch")

        assertEquals(HttpStatusCode.OK, shabtab.status)
        val body = shabtab.body()
        assertEquals("ناموجود", body["stock"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement("""{"key":"STOCK","label":"موجودی انبار","value":"تمام شد","swatches":null,"tone":"DARK"}"""),
            body["specTable"]!!.jsonArray.last(),
        )
    }

    @Test
    fun `color picks the color shown first`() = withApp {
        assertEquals("olive", page("nila-crossbody-bag?color=olive").body()["selectedColorKey"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, page("nila-crossbody-bag?color=Olive").status)
    }

    // ============ step 9 is done when ============

    private suspend fun ApplicationTestBuilder.totalOf(query: String): Long =
        client.get("$API_V1/products?$query").body()["meta"]!!.jsonObject["totalItems"]!!.jsonPrimitive.content.toLong()

    private suspend fun ApplicationTestBuilder.facetsOf(query: String): JsonObject = client.get("$API_V1/products/facets?$query").body()

    private fun JsonObject.counts(facet: String): Map<String, Long> =
        this[facet]!!.jsonArray.associate { it.jsonObject["value"]!!.jsonPrimitive.content to it.jsonObject["count"]!!.jsonPrimitive.content.toLong() }

    @Test
    fun `each color's count is what the list really gives with that color added, whatever else is filtered`() = withApp {
        for (filters in listOf("", "onSale=true", "category=cross", "inStock=true&maxPrice=1500000", "color=lilac")) {
            val colors = facetsOf(filters).counts("colors")
            // The list's own color filter is replaced by the one counted: that is what disjunctive counting means.
            val others = filters.split('&').filter { it.isNotEmpty() && !it.startsWith("color=") }
            colors.forEach { (color, count) ->
                assertEquals(totalOf((others + "color=$color").joinToString("&")), count, "color $color with [$filters]")
            }
        }
    }

    @Test
    fun `the category counts add up to the filtered list, as every bag has one category`() = withApp {
        for (filters in listOf("", "onSale=true", "color=black", "q=%DA%A9%DB%8C%D9%81")) {
            assertEquals(totalOf(filters), facetsOf(filters).counts("categories").values.sum(), "[$filters]")
        }
    }

    @Test
    fun `the menus' productCount is the facets' count without filters`() = withApp {
        val menu = ListCategoriesUc(ExposedTransactionManager(db), CategoryRepositoryImpl(), ProductQmRepositoryImpl())
            .execute(ListCategoriesUc.Qry).getOrNull()!!

        assertEquals(menu.associate { it.slug to it.productCount.toLong() }, facetsOf("").counts("categories"))
    }

    @Test
    fun `facets come with the list when asked for, and the price range spans the shop rounded to 10,000`() = withApp {
        val list = client.get("$API_V1/products?includeFacets=true&size=1").body()

        assertEquals(
            Json.parseToJsonElement("""{"min":590000,"max":3150000,"step":10000}"""),
            list["facets"]!!.jsonObject["price"],
        )
        assertEquals(16L, list["facets"]!!.jsonObject.counts("bagTypes").values.sum())
    }

    @Test
    fun `related to Mahak are the other shoulder bag first, never Mahak itself, eight by default`() = withApp {
        val related = client.get("$API_V1/products/${idOf("mahak-shoulder-bag")}/related").slugs()

        assertEquals(8, related.size)
        assertEquals("hasti-shoulder-bag", related.first())
        assertTrue("mahak-shoulder-bag" !in related)
        assertEquals(HttpStatusCode.NotFound, client.get("$API_V1/products/999999/related").status)
    }

    private suspend fun HttpResponse.slugs(): List<String> =
        Json.parseToJsonElement(bodyAsText()).jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content }
}
