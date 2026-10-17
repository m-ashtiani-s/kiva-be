package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /admin/products` and `GET /admin/products/{productId}` over kiva_test (step 1.4), with the panel's real stack
 * and products made through the real repositories.
 */
class WebV1AdminProductsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-04-02T09:00:00Z"))
    private val stack = PanelTestStack(clock)
    private val catalog = StoredCatalog()

    @AfterEach
    fun deleteWhatWasStored() {
        catalog.deleteAll()
        stack.deleteWhatWasStored()
    }

    private val manager get() = stack.callerWith(Role.CATALOG_MANAGER)

    private suspend fun ApplicationTestBuilder.get(path: String, caller: PanelTestStack.Caller = manager, vararg parameters: Pair<String, String>): HttpResponse =
        client.get("$API_V1$path") {
            header(HttpHeaders.Authorization, caller.bearer)
            parameters.forEach { (name, value) -> parameter(name, value) }
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun named(name: String, colors: List<String> = listOf("lilac"), by: PanelTestStack.Caller? = null): Product =
        catalog.newProduct(
            colors.map { StoredCatalog.NewColor(it) },
            details = ProductEventSamples.fullDetails.copy(name = ProductName.constructFromPersisted(name)),
            by = by?.user?.id ?: catalog.staff,
        )

    @Test
    fun `a product's page has the shop's fields and the panel's - ETag header and etag both W-quote-version`() = stack.app {
        val maker = stack.caller(PanelTestStack.phone(40), Role.CATALOG_MANAGER, name = "لیلا احمدی")
        val bag = named("یاسمن", colors = listOf("lilac", "black"), by = maker)
        catalog.stock(bag.variants[0].id) { it.adjust(StockDelta.constructFromPersisted(7), AdjustmentReason.RESTOCK, null, catalog.staff, catalog.now) }
        catalog.change(bag.id) { it.deactivateVariant(bag.variants[1].id, catalog.staff, catalog.now) }

        val response = get("/admin/products/${bag.id.value}")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        // Created, two colors, one deactivated: four events.
        assertEquals("W/\"4\"", response.headers[HttpHeaders.ETag])
        assertEquals("W/\"4\"", body.getValue("etag").jsonPrimitive.content)
        assertEquals("DRAFT", body.getValue("status").jsonPrimitive.content)
        assertEquals("لیلا احمدی", body.getValue("createdBy").jsonPrimitive.content)
        assertEquals("lilac", body.getValue("defaultColorKey").jsonPrimitive.content)
        assertEquals(listOf("lilac"), body.getValue("variants").jsonArray.map { it.jsonObject.getValue("color").jsonObject.getValue("key").jsonPrimitive.content })
        val adminVariants = body.getValue("adminVariants").jsonArray.map { it.jsonObject }
        assertEquals(listOf(true, false), adminVariants.map { it.getValue("isActive").jsonPrimitive.content.toBoolean() })
        assertEquals(7, adminVariants[0].getValue("stockOnHand").jsonPrimitive.int)
        assertEquals(7, adminVariants[0].getValue("stockAvailable").jsonPrimitive.int)
        assertEquals(0, adminVariants[0].getValue("stockAlertSubscribers").jsonPrimitive.int)
        assertEquals(JsonNull, adminVariants[0].getValue("barcode"))
        listOf("viewsCount", "conversionRate", "isWishlisted", "campaign", "stockAlert", "publishAt", "hoverImage").forEach {
            assertEquals(JsonNull, body.getValue(it), it)
        }
        assertEquals(0, body.getValue("perks").jsonArray.size)
        assertEquals(0, body.getValue("rating").jsonObject.getValue("count").jsonPrimitive.int)
    }

    @Test
    fun `a draft without colors is 200 with no default color, no colors and sold out`() = stack.app {
        val bare = catalog.newProduct(emptyList())

        val body = get("/admin/products/${bare.id.value}").json()

        assertEquals(JsonNull, body.getValue("defaultColorKey"))
        assertEquals(JsonNull, body.getValue("displayColorKey"))
        assertEquals(JsonNull, body.getValue("selectedColorKey"))
        assertEquals(0, body.getValue("colors").jsonArray.size)
        assertEquals(0, body.getValue("variants").jsonArray.size)
        assertEquals(0, body.getValue("adminVariants").jsonArray.size)
        val stock = body.getValue("stock").jsonObject
        assertEquals("OUT_OF_STOCK", stock.getValue("status").jsonPrimitive.content)
        assertEquals(JsonNull, stock.getValue("availableQuantity"))
        assertEquals(0, stock.getValue("maxOrderQuantity").jsonPrimitive.int)
        // The seeder-like maker of StoredCatalog has no account here.
        assertEquals("حساب حذف‌شده", body.getValue("createdBy").jsonPrimitive.content)
    }

    @Test
    fun `an archived product is 200 - one nobody has and one that is not a number are 404 PRODUCT_NOT_FOUND`() = stack.app {
        val bag = named("پونه")
        catalog.change(bag.id) { it.archive(catalog.staff, catalog.now) }

        assertEquals("ARCHIVED", get("/admin/products/${bag.id.value}").json().getValue("status").jsonPrimitive.content)
        listOf("987654321", "abc", "0").forEach { id ->
            val response = get("/admin/products/$id")
            assertEquals(HttpStatusCode.NotFound, response.status, id)
            assertEquals("PRODUCT_NOT_FOUND", response.json().getValue("code").jsonPrimitive.content)
        }
    }

    @Test
    fun `the list has every field of AdminProductListItem - status and stockStatus narrow it, a wrong one is 400`() = stack.app {
        val draft = named("سپیده")
        val active = named("سپیده")
        catalog.change(active.id) { it.publish(catalog.staff, catalog.now) }

        val all = get("/admin/products", parameters = arrayOf("q" to "سپیده")).json()
        val drafts = get("/admin/products", parameters = arrayOf("q" to "سپیده", "status" to "DRAFT")).json()
        val wrong = get("/admin/products", parameters = arrayOf("status" to "SOLD", "stockStatus" to "EMPTY", "sort" to "rating_desc"))

        val items = all.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf(active.id.value, draft.id.value).sortedDescending(), items.map { it.getValue("id").jsonPrimitive.content.toLong() })
        assertEquals(
            setOf("id", "sku", "name", "imageUrl", "category", "status", "price", "totalStock", "variantsCount", "lowStockVariants", "soldCount", "rating", "updatedAt"),
            items.first().keys,
        )
        assertEquals(2, all.getValue("meta").jsonObject.getValue("totalItems").jsonPrimitive.int)
        assertEquals(listOf(draft.id.value), drafts.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content.toLong() })
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        val fields = wrong.json().getValue("errors").jsonArray.map { it.jsonObject.getValue("field").jsonPrimitive.content }
        assertTrue("sort" in fields, fields.toString())
    }

    @Test
    fun `a wrong status and stockStatus are both 400 on their parameters`() = stack.app {
        val response = get("/admin/products", parameters = arrayOf("status" to "SOLD", "stockStatus" to "EMPTY"))

        assertEquals(
            listOf("status" to "PRODUCT_STATUS_INVALID", "stockStatus" to "STOCK_STATUS_INVALID"),
            response.json().getValue("errors").jsonArray.map { it.jsonObject.getValue("field").jsonPrimitive.content to it.jsonObject.getValue("code").jsonPrimitive.content },
        )
    }

    @Test
    fun `an OPERATOR is 403 on both`() = stack.app {
        val operator = stack.callerWith(Role.OPERATOR)

        assertEquals(HttpStatusCode.Forbidden, get("/admin/products", operator).status)
        assertEquals(HttpStatusCode.Forbidden, get("/admin/products/1", operator).status)
    }
}
