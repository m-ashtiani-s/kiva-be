package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.repository.ProductFilter
import com.kiva.application.usecase.product.ProductCards
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.application.usecase.wishlist.WishlistHearts
import com.kiva.common.pagination.Pageable
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.orFail
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
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
import kotlinx.serialization.json.JsonElement
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
import java.time.Instant
import kotlin.test.assertEquals

/**
 * The heart on the shop's uncached cards and the account's header over kiva_test (roadmap step 9.4): null for a guest
 * everywhere, true or false for a customer on the list, the product's page, the related products and the cart, read in
 * one statement per page; and every number of `/me/dashboard` the same as the page it links to.
 */
class WebV1WishlistHeartsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val returns = AskedReturns(panel, clock)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private fun productOf(variantId: Long): Long = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variantId }.single()[ProductVariantQmTbl.productId]
    }

    private val nilaBlack by lazy { panel.purchase.variantOf("nila-crossbody-bag", "black") }
    private val nilaId by lazy { productOf(nilaBlack) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.get(path: String, who: PanelTestStack.Caller?): JsonElement {
        val answer = client.get("$API_V1$path") { who?.let { header(HttpHeaders.Authorization, it.bearer) } }
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        return Json.parseToJsonElement(answer.bodyAsText())
    }

    /** The hearts of a list of cards, by product id. */
    private fun hearts(cards: JsonElement): Map<String?, String?> = cards.jsonArray.associate { it.jsonObject.text("id") to it.jsonObject.text("isWishlisted") }

    @Test
    fun `a guest sees no heart anywhere - a customer sees theirs, and false on the others, on the list, the page, the related and the cart`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(901))
        client.put("$API_V1/me/wishlist/items/$nilaId") { header(HttpHeaders.Authorization, sara.bearer) }
        client.post("$API_V1/cart/items") {
            header(HttpHeaders.Authorization, sara.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$nilaBlack,"quantity":1}""")
        }

        val guestList = hearts(get("/products?size=48", null).jsonObject.getValue("items"))
        val list = hearts(get("/products?size=48", sara).jsonObject.getValue("items"))
        assertEquals(setOf<String?>(null), guestList.values.toSet())
        assertEquals("true", list[nilaId.toString()])
        assertEquals(setOf<String?>("false"), (list - nilaId.toString()).values.toSet())

        assertEquals(JsonNull, get("/products/nila-crossbody-bag", null).jsonObject["isWishlisted"])
        val page = get("/products/nila-crossbody-bag", sara).jsonObject
        assertEquals("true", page.text("isWishlisted"))
        assertEquals(ContractSchemas.propertiesOf("ProductDetail").filter { it in page.keys }.toSet(), page.keys.filter { it in ContractSchemas.propertiesOf("ProductDetail") }.toSet())

        val anotherId = (list.keys - nilaId.toString()).first()!!
        val related = hearts(get("/products/$anotherId/related?limit=24", sara))
        assertEquals("true", related[nilaId.toString()] ?: "true", "nila, if among the related, has its heart")
        assertEquals(setOf<String?>("false"), (related - nilaId.toString()).values.toSet())
        assertEquals(setOf<String?>(null), hearts(get("/products/$anotherId/related?limit=24", null)).values.toSet())

        val cart = get("/cart", sara).jsonObject.getValue("items").jsonArray.single().jsonObject
        assertEquals("true", cart.text("isWishlisted"))
        val guestCart = client.post("$API_V1/cart/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$nilaBlack,"quantity":1}""")
        }.json()
        assertEquals(JsonNull, guestCart.getValue("cart").jsonObject.getValue("items").jsonArray.single().jsonObject["isWishlisted"])

        val expired = client.get("$API_V1/products") { header(HttpHeaders.Authorization, "Bearer not.a.token") }
        assertEquals(HttpStatusCode.Unauthorized, expired.status, "a broken token is not a guest")
    }

    @Test
    fun `the hearts of a page of 24 cards are one statement - no more than for one card`() {
        val sara = panel.caller(PanelTestStack.phone(901))
        val hearts = WishlistHearts.of(panel.wishlists)
        fun statementsFor(size: Int): Int = transaction(db) {
            val qms = panel.productQm.find(ProductFilter(), Pageable.makeNew(1, size, ProductSorts.DEFAULT, ProductSorts.names).orFail()).items
            val categories = panel.categories.findAll().associateBy { it.slug }
            val palette = panel.colors.findAll().associateBy { it.key }
            val cards = qms.map { ProductCards.cardOf(it, categories.getValue(it.categorySlug), palette, clock.now, com.kiva.common.valueobject.Money.ZERO, 3) }
            val before = statementCount
            hearts.mark(cards, sara.user.id)
            statementCount - before
        }

        assertEquals(1, statementsFor(1))
        assertEquals(1, statementsFor(24))
    }

    @Test
    fun `the dashboard - every number the same as the page it links to, the greeting with the first name`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(901), name = "سارا محمدی")
        val code = with(returns) { delivered(sara, nilaBlack) }
        val fresh = returns.fresh(sara)
        client.put("$API_V1/me/wishlist/items/$nilaId") { header(HttpHeaders.Authorization, fresh.bearer) }

        val dashboard = get("/me/dashboard", fresh).jsonObject

        assertEquals(ContractSchemas.propertiesOf("AccountDashboard"), dashboard.keys)
        assertEquals("سلام سارا", dashboard.text("greeting"))
        val stats = dashboard.getValue("stats").jsonObject
        val nav = dashboard.getValue("navCounts").jsonObject
        val orders = get("/me/orders", fresh).jsonObject
        val counts = orders.getValue("counts").jsonObject
        assertEquals(listOf(counts.text("all"), counts.text("current")), listOf(stats.text("ordersTotal"), stats.text("ordersActive")))
        assertEquals(counts.text("all"), nav.text("orders"))
        val media = orders.getValue("items").jsonArray.sumOf { it.jsonObject.getValue("preShipmentMedia").jsonObject.text("count")!!.toInt() }
        assertEquals(media.toString(), stats.text("mediaReceived"))
        assertEquals(true, media > 0, "the delivered order's photos")
        val wishlist = get("/me/wishlist", fresh).jsonObject.text("count")
        assertEquals(listOf(wishlist, wishlist), listOf(stats.text("wishlistCount"), nav.text("wishlist")))
        assertEquals(get("/me/addresses", fresh).jsonArray.size.toString(), nav.text("addresses"))
        assertEquals(get("/me/reviews", fresh).jsonObject.getValue("counts").jsonObject.text("total"), nav.text("reviews"))
        assertEquals(get("/me/reviews/pending-products", fresh).jsonArray.size.toString(), dashboard.text("pendingReviewCount"))
        assertEquals("1", dashboard.text("pendingReviewCount"), "the delivered bag awaits a review")
        val reservation = client.get("$API_V1/me/reservation") { header(HttpHeaders.Authorization, fresh.bearer) }
        assertEquals(HttpStatusCode.NoContent, reservation.status)
        assertEquals(JsonNull, dashboard["activeReservation"])
        assertEquals(code.value, orders.getValue("items").jsonArray.single().jsonObject.text("code"))

        val nobody = panel.caller(PanelTestStack.phone(902))
        val empty = get("/me/dashboard", nobody).jsonObject
        assertEquals("سلام دوست عزیز", empty.text("greeting"))
        assertEquals(listOf("0", "0", "0", "0"), empty.getValue("stats").jsonObject.let { s -> listOf("ordersTotal", "ordersActive", "mediaReceived", "wishlistCount").map { s.text(it) } })
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1/me/dashboard").status)
    }
}
