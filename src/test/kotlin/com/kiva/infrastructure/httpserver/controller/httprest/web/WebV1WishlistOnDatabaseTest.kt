package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.wishlist.usecase.command.AddToWishlistUc
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.WishlistItemTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The customer's wishlist over kiva_test (roadmap step 9.1): saving, saving again, removing, emptying; the 200 limit
 * under twenty adds at once; the price drop; a draft hidden and back, an archived product gone; a list longer than the
 * product filter's 60 ids, whole and in order. Every answer held against the contract.
 */
class WebV1WishlistOnDatabaseTest {

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

    private val nilaBlack by lazy { panel.purchase.variantOf("nila-crossbody-bag", "black") }
    private val nilaId by lazy { productOf(nilaBlack) }

    private fun productOf(variantId: Long): Long = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variantId }.single()[ProductVariantQmTbl.productId]
    }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

    /** [n] new products in the shop, each published a minute after the last, with a lilac color. */
    private fun published(n: Int): List<Long> = (1..n).map {
        val product = catalog.newProduct(listOf(NewColor("lilac")), at = clock.now)
        catalog.change(product.id) { p -> p.publish(catalog.staff, clock.now) }
        product.id.value
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.save(productId: Any, who: PanelTestStack.Caller, body: String? = null): HttpResponse =
        client.put("$API_V1/me/wishlist/items/$productId") {
            header(HttpHeaders.Authorization, who.bearer)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun ApplicationTestBuilder.remove(productId: Any, who: PanelTestStack.Caller): HttpResponse =
        client.delete("$API_V1/me/wishlist/items/$productId") { header(HttpHeaders.Authorization, who.bearer) }

    private suspend fun ApplicationTestBuilder.list(who: PanelTestStack.Caller, query: String = ""): JsonObject {
        val answer = client.get("$API_V1/me/wishlist$query") { header(HttpHeaders.Authorization, who.bearer) }
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        return answer.json()
    }

    private suspend fun ApplicationTestBuilder.ids(who: PanelTestStack.Caller): JsonObject =
        client.get("$API_V1/me/wishlist/ids") { header(HttpHeaders.Authorization, who.bearer) }.json()

    private fun JsonObject.itemIds(): List<String?> = getValue("items").jsonArray.map { it.jsonObject.getValue("product").jsonObject.text("id") }

    private fun rowsOf(who: PanelTestStack.Caller): List<Long> = transaction(db) {
        WishlistItemTbl.selectAll().where { WishlistItemTbl.userId eq who.user.id.value }.map { it[WishlistItemTbl.productId] }
    }

    @Test
    fun `saved, saved again in another color - the color changes, not the price - removed twice, both 200 - an id nobody had 404`() = panel.app {
        val sara = customer(1)

        val saved = save(nilaId, sara, """{"colorKey":"black"}""")
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val toggle = saved.json()
        assertEquals(ContractSchemas.propertiesOf("WishlistToggleResponse"), toggle.keys)
        assertEquals(listOf(nilaId.toString(), "true", "1", "به علاقه‌مندی‌ها اضافه شد"), listOf(toggle.text("productId"), toggle.text("wishlisted"), toggle.text("count"), toggle.text("message")))
        val priceAtAdd = list(sara).getValue("items").jsonArray.single().jsonObject.text("priceAtAdd")

        clock.advance(Duration.ofHours(1))
        val later = customer(1) // a fresh token, an hour later
        assertEquals(HttpStatusCode.OK, save(nilaId, later, """{"colorKey":"olive"}""").status)
        assertEquals(HttpStatusCode.OK, save(nilaId, later).status, "no body: the color stays")

        val item = list(later).getValue("items").jsonArray.single().jsonObject
        assertEquals(ContractSchemas.propertiesOf("WishlistItem"), item.keys)
        assertEquals(listOf("olive", priceAtAdd, "2026-10-12T08:00:00Z", "true"), listOf(item.text("preferredColorKey"), item.text("priceAtAdd"), item.text("addedAt"), item.getValue("product").jsonObject.text("isWishlisted")))

        val first = remove(nilaId, later)
        val second = remove(nilaId, later)
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK), listOf(first.status, second.status))
        assertEquals(listOf("false", "0", "از علاقه‌مندی‌ها حذف شد"), second.json().let { listOf(it.text("wishlisted"), it.text("count"), it.text("message")) })
        assertEquals(HttpStatusCode.NotFound, remove(999_999_999, later).status)
        assertEquals(HttpStatusCode.NotFound, remove("abc", later).status)
        assertTrue(rowsOf(later).isEmpty())
    }

    @Test
    fun `not in the shop 404 - a color it doesn't come in 400 COLOR_NOT_FOUND - a color key that can't be one 400 - no login 401`() = panel.app {
        val sara = customer(1)
        val draft = catalog.newProduct(listOf(NewColor("lilac"))).id.value

        assertEquals(HttpStatusCode.NotFound, save(draft, sara).status)
        assertEquals(HttpStatusCode.NotFound, save(999_999_999, sara).status)
        val noSuchColor = save(nilaId, sara, """{"colorKey":"lilac"}""").json()
        assertEquals("COLOR_NOT_FOUND", noSuchColor.getValue("errors").jsonArray.single().jsonObject.text("code"))
        val badKey = save(nilaId, sara, """{"colorKey":"Lilac!"}""").json()
        assertEquals(listOf("colorKey", "COLOR_KEY_INVALID"), badKey.getValue("errors").jsonArray.single().jsonObject.let { listOf(it.text("field"), it.text("code")) })
        assertEquals(HttpStatusCode.Unauthorized, client.put("$API_V1/me/wishlist/items/$nilaId").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1/me/wishlist").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1/me/wishlist/ids").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("$API_V1/me/wishlist").status)
        assertTrue(rowsOf(sara).isEmpty())
    }

    @Test
    fun `20 adds at once onto 195 rows leave exactly 200 - the 201st is 422 WISHLIST_LIMIT_REACHED with the limit`() = panel.app {
        val sara = customer(1)
        // 195 rows of products that are not in the shop: hidden, but counted (decision 6).
        transaction(db) {
            WishlistItemTbl.batchInsert((1L..195L).toList()) { n ->
                this[WishlistItemTbl.userId] = sara.user.id.value
                this[WishlistItemTbl.productId] = 8_000_000 + n
                this[WishlistItemTbl.priceAtAdd] = 1_000_000
                this[WishlistItemTbl.addedOn] = clock.now.atOffset(ZoneOffset.UTC)
            }
        }
        val products = published(20)
        val pool = Executors.newFixedThreadPool(20)
        val results = try {
            val start = CountDownLatch(1)
            val futures = products.map { id ->
                pool.submit<Result<*, *>> {
                    start.await()
                    runBlocking { panel.wishlist.add.execute(AddToWishlistUc.Cmd(sara.user.id, id.toString(), null)) }
                }
            }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(5, results.count { it.getOrNull() != null })
        assertEquals(15, results.count { it.failureOrNull() == AddToWishlistUc.Failure.LimitReached })
        assertEquals(200, rowsOf(sara).size)
        val refused = save(nilaId, sara)
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals(listOf("WISHLIST_LIMIT_REACHED", "200"), refused.json().let { listOf(it.text("code"), it.getValue("meta").jsonObject.text("limit")) })
        assertEquals("5", ids(sara).text("count"), "only the visible ones")
    }

    @Test
    fun `cheaper since it was saved - the drop and its label - half a percent or dearer is none`() = panel.app {
        val sara = customer(1)
        save(nilaId, sara, """{"colorKey":"black"}""")
        val saved = list(sara).getValue("items").jsonArray.single().jsonObject.text("priceAtAdd")!!.toLong()

        fun priceNow(toman: Long) = transaction(db) {
            ProductVariantQmTbl.update({ ProductVariantQmTbl.variantId eq nilaBlack }) { it[price] = toman }
        }
        fun drop(item: JsonObject) = item.getValue("items").jsonArray.single().jsonObject["priceDrop"]

        priceNow(saved * 85 / 100)
        val dropped = drop(list(sara))!!.jsonObject
        assertEquals(listOf((saved - saved * 85 / 100).toString(), "15", "از وقتی ذخیره کردی ۱۵٪ ارزون‌تر شده"), listOf(dropped.text("amount"), dropped.text("percent"), dropped.text("label")))
        priceNow(saved - saved / 200)
        assertEquals(JsonNull, drop(list(sara)), "half a percent")
        priceNow(saved + 100_000)
        assertEquals(JsonNull, drop(list(sara)), "dearer")
    }

    @Test
    fun `a draft is hidden from the list, the ids and the count and comes back when published - archived it is gone for good`() = panel.app {
        val sara = customer(1)
        val (kept, drafted, archived) = published(3)
        listOf(kept, drafted, archived).forEach { save(it, sara); clock.advance(Duration.ofMinutes(1)) }

        fun status(productId: Long, status: ProductStatus) = transaction(db) {
            ProductQmTbl.update({ ProductQmTbl.productId eq productId }) { it[ProductQmTbl.status] = status }
        }
        status(drafted, ProductStatus.DRAFT)
        assertEquals(listOf(archived, kept).map { it.toString() }, list(sara).itemIds())
        assertEquals(listOf("2", "2"), list(sara).let { listOf(it.text("count"), ids(sara).text("count")) })
        assertEquals(listOf(archived, kept), ids(sara).getValue("productIds").jsonArray.map { it.jsonPrimitive.content.toLong() })
        assertEquals(3, rowsOf(sara).size, "the draft's row stays")

        status(drafted, ProductStatus.ACTIVE)
        assertEquals(listOf(archived, drafted, kept).map { it.toString() }, list(sara).itemIds())

        val admin = panel.callerWith(Role.ADMIN)
        val gone = client.delete("$API_V1/admin/products/$archived") { header(HttpHeaders.Authorization, admin.bearer) }
        assertEquals(HttpStatusCode.NoContent, gone.status, gone.bodyAsText())
        assertEquals(listOf(drafted, kept).sorted(), rowsOf(sara).sorted())
    }

    @Test
    fun `a list of 120 - longer than the product filter's 60 ids - whole and in order, newest first - in stock counted over all of it`() = panel.app {
        val sara = customer(1)
        val products = published(120)
        products.forEach {
            assertTrue(panel.wishlist.add.execute(AddToWishlistUc.Cmd(sara.user.id, it.toString(), null)).getOrNull() != null)
            clock.advance(Duration.ofSeconds(1))
        }
        val inStock = products[7]
        catalog.stock(catalog.read(com.kiva.domain.aggregate.product.model.valueobject.ProductId.constructFromPersisted(inStock)).variants.single().id) {
            it.adjust(StockDelta.constructFromPersisted(2), AdjustmentReason.RESTOCK, null, catalog.staff, clock.now)
        }

        val page1 = list(sara, "?size=100")
        val page2 = list(sara, "?size=100&page=2")

        assertEquals(setOf("items", "meta", "count", "inStockCount", "share"), page1.keys)
        assertEquals(products.reversed().map { it.toString() }, page1.itemIds() + page2.itemIds())
        assertEquals(listOf("120", "1"), listOf(page1.text("count"), page2.text("inStockCount")))
        assertEquals(listOf("120", "2", "false"), page2.getValue("meta").jsonObject.let { listOf(it.text("totalItems"), it.text("totalPages"), it.text("hasNext")) })
        assertEquals(JsonNull, page1["share"])
        assertEquals(products.reversed(), ids(sara).getValue("productIds").jsonArray.map { it.jsonPrimitive.content.toLong() })
        assertEquals(48, list(sara).getValue("items").jsonArray.size, "48 by default")
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1/me/wishlist?size=101") { header(HttpHeaders.Authorization, sara.bearer) }.status)

        val emptied = client.delete("$API_V1/me/wishlist") { header(HttpHeaders.Authorization, sara.bearer) }
        assertEquals(HttpStatusCode.NoContent, emptied.status)
        assertTrue(rowsOf(sara).isEmpty())
        assertNull(list(sara).getValue("items").jsonArray.firstOrNull())
    }
}
