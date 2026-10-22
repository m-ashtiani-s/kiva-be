package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.WishlistItemTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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

/**
 * The wishlist and the cart over kiva_test (roadmap step 9.2): «همه به سبد» with its color choice and its skipped
 * products, and a cart line moved to the wishlist, all or nothing.
 */
class WebV1WishlistCartOnDatabaseTest {

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

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

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

    private suspend fun ApplicationTestBuilder.save(product: Product, who: PanelTestStack.Caller, colorKey: String) {
        val saved = client.put("$API_V1/me/wishlist/items/${product.id.value}") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"colorKey":"$colorKey"}""")
        }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        clock.advance(Duration.ofSeconds(1))
    }

    private suspend fun ApplicationTestBuilder.addToCart(variantId: Long, who: PanelTestStack.Caller, quantity: Int = 1): JsonObject {
        val added = client.post("$API_V1/cart/items") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$variantId,"quantity":$quantity}""")
        }
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
        return added.json()
    }

    private suspend fun ApplicationTestBuilder.cartLines(who: PanelTestStack.Caller): List<Pair<String?, String?>> =
        client.get("$API_V1/cart") { header(HttpHeaders.Authorization, who.bearer) }.json().getValue("items").jsonArray
            .map { it.jsonObject.let { line -> line.getValue("variantId").jsonPrimitive.content to line.text("quantity") } }

    private fun rowsOf(who: PanelTestStack.Caller): Map<Long, String?> = transaction(db) {
        WishlistItemTbl.selectAll().where { WishlistItemTbl.userId eq who.user.id.value }.associate { it[WishlistItemTbl.productId] to it[WishlistItemTbl.preferredColorKey] }
    }

    @Test
    fun `all to the cart - another color when the saved one is gone, OUT_OF_STOCK, ALREADY_AT_MAX - one each, the list untouched`() = panel.app {
        val sara = customer(1)
        val switched = published(listOf("lilac", "black"), stock = mapOf("black" to 2))
        val soldOut = published(listOf("lilac"))
        val full = published(listOf("lilac"), stock = mapOf("lilac" to 1))
        save(switched, sara, "lilac")
        save(soldOut, sara, "lilac")
        save(full, sara, "lilac")
        addToCart(full.variant("lilac"), sara)
        val before = rowsOf(sara)

        val answer = client.post("$API_V1/me/wishlist/add-to-cart") { header(HttpHeaders.Authorization, sara.bearer) }

        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        val body = answer.json()
        assertEquals(ContractSchemas.propertiesOf("WishlistAddToCartResponse"), body.keys)
        assertEquals(listOf("1", "۱ کیف به سبد اضافه شد"), listOf(body.text("addedCount"), body.text("message")))
        assertEquals(
            setOf(full.id.value.toString() to "ALREADY_AT_MAX", soldOut.id.value.toString() to "OUT_OF_STOCK"),
            body.getValue("skipped").jsonArray.map { it.jsonObject.text("productId") to it.jsonObject.text("reason") }.toSet(),
        )
        assertEquals(setOf(full.variant("lilac").toString() to "1", switched.variant("black").toString() to "1"), cartLines(sara).toSet())
        assertEquals(before, rowsOf(sara), "the list is not changed")
    }

    @Test
    fun `only the products asked for - one not in the list ignored - more than 200 or a bad id 400 - an empty list adds nothing`() = panel.app {
        val sara = customer(1)
        val first = published(listOf("lilac"), stock = mapOf("lilac" to 3))
        val second = published(listOf("lilac"), stock = mapOf("lilac" to 3))
        save(first, sara, "lilac")
        save(second, sara, "lilac")

        suspend fun toCart(body: String) = client.post("$API_V1/me/wishlist/add-to-cart") {
            header(HttpHeaders.Authorization, sara.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val only = toCart("""{"productIds":[${second.id.value},999999999]}""").json()
        assertEquals("1", only.text("addedCount"))
        assertEquals(listOf(second.variant("lilac").toString() to "1"), cartLines(sara))
        val tooMany = toCart("""{"productIds":[${(1..201).joinToString(",")}]}""")
        assertEquals(listOf("productIds", "TOO_MANY_ITEMS"), tooMany.json().getValue("errors").jsonArray.single().jsonObject.let { listOf(it.text("field"), it.text("code")) })
        assertEquals(listOf("productIds.1"), toCart("""{"productIds":[1,0]}""").json().getValue("errors").jsonArray.map { it.jsonObject.text("field") })

        val nobody = customer(2)
        val empty = client.post("$API_V1/me/wishlist/add-to-cart") { header(HttpHeaders.Authorization, nobody.bearer) }
        assertEquals(listOf("0", "هیچ کیفی به سبد اضافه نشد"), empty.json().let { listOf(it.text("addedCount"), it.text("message")) })
        assertEquals(HttpStatusCode.Unauthorized, client.post("$API_V1/me/wishlist/add-to-cart").status)
    }

    @Test
    fun `a cart line moved - out of the cart, into the list with its color - a full list 422 and the line stays - a guest 401, a line of none 404`() = panel.app {
        val sara = customer(1)
        val bag = published(listOf("lilac", "black"), stock = mapOf("black" to 2))
        val cart = addToCart(bag.variant("black"), sara, quantity = 2)
        val itemId = cart.getValue("cart").jsonObject.getValue("items").jsonArray.single().jsonObject.text("id")

        val moved = client.post("$API_V1/cart/items/$itemId/move-to-wishlist") { header(HttpHeaders.Authorization, sara.bearer) }

        assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
        val body = moved.json()
        assertEquals("به علاقه‌مندی‌ها منتقل شد", body.text("message"))
        assertEquals(listOf(bag.variant("black").toString(), "2"), body.getValue("removedItem").jsonObject.let { listOf(it.text("variantId"), it.text("quantity")) })
        assertEquals(emptyList(), cartLines(sara))
        assertEquals(mapOf(bag.id.value to "black"), rowsOf(sara))

        // A full list without the bag: nothing changes.
        val other = customer(2)
        transaction(db) {
            WishlistItemTbl.batchInsert((1L..200L).toList()) { n ->
                this[WishlistItemTbl.userId] = other.user.id.value
                this[WishlistItemTbl.productId] = 8_000_000 + n
                this[WishlistItemTbl.priceAtAdd] = 1_000_000
                this[WishlistItemTbl.addedOn] = clock.now.atOffset(ZoneOffset.UTC)
            }
        }
        val theirs = addToCart(bag.variant("black"), other).getValue("cart").jsonObject.getValue("items").jsonArray.single().jsonObject.text("id")
        val refused = client.post("$API_V1/cart/items/$theirs/move-to-wishlist") { header(HttpHeaders.Authorization, other.bearer) }
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals(listOf("WISHLIST_LIMIT_REACHED", "200"), refused.json().let { listOf(it.text("code"), it.getValue("meta").jsonObject.text("limit")) })
        assertEquals(listOf(bag.variant("black").toString() to "1"), cartLines(other))
        assertEquals(200, rowsOf(other).size)

        assertEquals(HttpStatusCode.NotFound, client.post("$API_V1/cart/items/$theirs/move-to-wishlist") { header(HttpHeaders.Authorization, sara.bearer) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("$API_V1/cart/items/not-a-line/move-to-wishlist") { header(HttpHeaders.Authorization, sara.bearer) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("$API_V1/cart/items/$theirs/move-to-wishlist").status)
    }
}
