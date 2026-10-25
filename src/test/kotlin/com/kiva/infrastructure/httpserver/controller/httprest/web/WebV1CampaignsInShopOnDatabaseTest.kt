package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.priceTag
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.seed.SeededCatalog
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.CampaignTbl
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A campaign in the shop over kiva_test (roadmap step 11.2): `GET /campaigns/active` only while it runs, its products
 * (discounted, with a color left, the biggest discount first), `GET /products?campaign=`, and the countdown on a
 * product's page; and never a changed price.
 */
class WebV1CampaignsInShopOnDatabaseTest {

    // Two days before the campaign below.
    private val clock = MutableClock(Instant.parse("2026-10-19T08:00:00Z"))
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

    private val starts = Instant.parse("2026-10-21T00:00:00Z")
    private val ends = Instant.parse("2026-11-01T00:00:00Z")

    /** Moves the clock to [moment]. */
    private fun at(moment: Instant) = clock.advance(Duration.between(clock.now, moment))

    private fun published(price: Long, compareAt: Long? = null, bags: Int = 3): Product {
        val product = catalog.newProduct(listOf(NewColor("lilac")), priceTag = priceTag(price, compareAt), at = clock.now)
        catalog.change(product.id) { it.publish(catalog.staff, clock.now) }
        if (bags > 0) catalog.stock(product.variants.single().id) { it.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, catalog.staff, clock.now) }
        return product
    }

    private suspend fun ApplicationTestBuilder.campaign(productIds: List<Product>?) {
        val ids = productIds?.joinToString(",", prefix = ""","productIds":[""", postfix = "]") { it.id.value.toString() }.orEmpty()
        val made = client.post("$API_V1/admin/campaigns") {
            header(HttpHeaders.Authorization, panel.callerWith(Role.CONTENT_EDITOR).bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"paeez-1405","title":"جشنواره پاییزه","startsAt":"$starts","endsAt":"$ends"$ids}""")
        }
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.ids(path: String): List<Long> =
        client.get("$API_V1$path").json().getValue(if (path.startsWith("/products")) "items" else "products").jsonArray.map { it.jsonObject.text("id")!!.toLong() }

    @Test
    fun `204 before it starts and after it ends - while it runs the campaign with its discounted products in stock, the biggest discount first`() = panel.app {
        val small = published(900_000, compareAt = 1_000_000)
        val big = published(700_000, compareAt = 1_000_000)
        val soldOut = published(500_000, compareAt = 1_000_000, bags = 0)
        val full = published(800_000)
        campaign(listOf(small, big, soldOut, full))

        assertEquals(HttpStatusCode.NoContent, client.get("$API_V1/campaigns/active").status)
        at(starts)
        val running = client.get("$API_V1/campaigns/active")
        assertEquals(HttpStatusCode.OK, running.status)
        val body = running.json()
        assertEquals(ContractSchemas.propertiesOf("Campaign"), body.keys)
        assertEquals(listOf(big.id.value, small.id.value), body.getValue("products").jsonArray.map { it.jsonObject.text("id")!!.toLong() })
        assertEquals(listOf(starts.toString(), ends.toString(), "/products?campaign=paeez-1405"), listOf(body.text("serverTime"), body.text("endsAt"), body.text("seeAllUrl")))
        assertEquals(setOf<String?>(null), body.getValue("products").jsonArray.map { it.jsonObject.text("isWishlisted") }.toSet())

        at(ends)
        assertEquals(HttpStatusCode.NoContent, client.get("$API_V1/campaigns/active").status)
    }

    @Test
    fun `no products chosen is every discounted product - three chosen with one at full price shows two`() = panel.app {
        val a = published(900_000, compareAt = 1_000_000)
        val b = published(600_000, compareAt = 1_000_000)
        val full = published(800_000)
        at(starts)

        campaign(null)
        val all = ids("/campaigns/active")
        assertTrue(all.containsAll(listOf(a.id.value, b.id.value)) && full.id.value !in all, "$all")
        assertTrue(all.indexOf(b.id.value) < all.indexOf(a.id.value))
        transaction(TestDatabase.database) { CampaignTbl.deleteAll() }

        campaign(listOf(a, b, full))
        assertEquals(listOf(b.id.value, a.id.value), ids("/campaigns/active"))
    }

    @Test
    fun `GET products with the campaign - its discounted ones, the sold out last, titled and chipped by it - after it ends an empty list`() = panel.app {
        val a = published(900_000, compareAt = 1_000_000)
        val soldOut = published(500_000, compareAt = 1_000_000, bags = 0)
        val full = published(800_000)
        campaign(listOf(a, soldOut, full))
        at(starts)

        val list = client.get("$API_V1/products?campaign=paeez-1405&sort=discount_desc").json()
        assertEquals(listOf(a.id.value, soldOut.id.value), list.getValue("items").jsonArray.map { it.jsonObject.text("id")!!.toLong() })
        assertEquals("جشنواره پاییزه", list.text("title"))
        assertEquals(listOf("campaign" to "paeez-1405"), list.getValue("appliedFilters").jsonArray.map { it.jsonObject.text("key") to it.jsonObject.text("value") })

        at(ends.plus(Duration.ofMinutes(1)))
        assertEquals(emptyList(), ids("/products?campaign=paeez-1405"))
        assertEquals(emptyList(), ids("/products?campaign=nobodys"))
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1/products?campaign=Bad_Slug").status)
    }

    @Test
    fun `a discounted product of the campaign shows its countdown while it runs, null after - its prices the same before, during and after`() = panel.app {
        val sale = published(700_000, compareAt = 1_000_000)
        val full = published(800_000)
        campaign(listOf(sale, full))
        val page = { id: Long -> runBlocking { client.get("$API_V1/products/$id").json() } }
        val before = page(sale.id.value).getValue("price")

        at(starts)
        val during = page(sale.id.value)
        assertEquals(listOf("paeez-1405", "جشنواره پاییزه", ends.toString()), during.getValue("campaign").jsonObject.let { listOf(it.text("slug"), it.text("title"), it.text("endsAt")) })
        assertEquals(JsonNull, page(full.id.value)["campaign"], "no discount, no countdown")

        at(ends)
        val after = page(sale.id.value)
        assertEquals(JsonNull, after["campaign"])
        assertEquals(before, during.getValue("price"))
        assertEquals(before, after.getValue("price"))
    }
}
