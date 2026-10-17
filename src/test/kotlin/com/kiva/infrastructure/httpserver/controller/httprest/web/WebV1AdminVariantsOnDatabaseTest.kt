package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST`, `PUT` and `DELETE /admin/products/{productId}/variants*` over kiva_test (step 1.7), with the panel's real stack.
 * Products are made through the real repositories; their colors' ids come from the read model's sequence.
 */
class WebV1AdminVariantsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-04-02T09:00:00Z"))
    private val stack = PanelTestStack(clock, FakeMediaProcessor())
    private val catalog = StoredCatalog()
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() {
        catalog.deleteAll()
        stack.deleteWhatWasStored()
    }

    private val manager get() = stack.callerWith(Role.CATALOG_MANAGER)

    // ============ helpers ============

    private suspend fun ApplicationTestBuilder.send(method: String, path: String, body: String? = null): HttpResponse {
        val block: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, manager.bearer)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> client.get("$API_V1$path", block)
            "POST" -> client.post("$API_V1$path", block)
            "PUT" -> client.put("$API_V1$path", block)
            "DELETE" -> client.delete("$API_V1$path", block)
            else -> error(method)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.code(): String = json().getValue("code").jsonPrimitive.content
    private suspend fun HttpResponse.errors(): List<String> =
        json()["errors"]?.jsonArray?.map { "${it.jsonObject.getValue("field").jsonPrimitive.content} ${it.jsonObject.getValue("code").jsonPrimitive.content}" }.orEmpty()

    private suspend fun ApplicationTestBuilder.upload(): String = client.post("$API_V1/admin/media") {
        header(HttpHeaders.Authorization, manager.bearer)
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("purpose", "PRODUCT")
                    append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                },
            ),
        )
    }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.json().getValue("id").jsonPrimitive.content

    private fun product(colors: List<String> = listOf("lilac"), published: Boolean = false): Product {
        val made = catalog.newProduct(colors.map { NewColor(it) })
        return if (published) catalog.change(made.id) { it.publish(catalog.staff, catalog.now) } else made
    }

    private suspend fun ApplicationTestBuilder.page(id: ProductId): JsonObject = send("GET", "/admin/products/${id.value}").json()

    private fun JsonObject.colorKeys(list: String): List<String> =
        getValue(list).jsonArray.map { it.jsonObject.getValue("color").jsonObject.getValue("key").jsonPrimitive.content }

    private fun JsonObject.adminVariant(key: String): JsonObject =
        getValue("adminVariants").jsonArray.map { it.jsonObject }.single { it.getValue("color").jsonObject.getValue("key").jsonPrimitive.content == key }

    private fun rows(action: String, variantId: Long): List<ResultRow> = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.action eq action) and (AuditLogTbl.entityId eq variantId.toString()) }.toList()
    }

    private fun changedFields(row: ResultRow): Map<String, Pair<String?, String?>> =
        row[AuditLogTbl.changes].orEmpty().associate {
            val change = it.jsonObject
            change.getValue("field").jsonPrimitive.content to
                (change["from"]?.takeIf { v -> v != JsonNull }?.jsonPrimitive?.content to change["to"]?.takeIf { v -> v != JsonNull }?.jsonPrimitive?.content)
        }

    // ============ adding ============

    @Test
    fun `a color with stock 8 and two photos is 201 as AdminVariant - in the shop with its photos and its stock`() = stack.app {
        val bag = product(published = true)
        val front = upload()
        val back = upload()

        val response = send(
            "POST", "/admin/products/${bag.id.value}/variants",
            """{"colorKey":"black","stockQuantity":8,"media":[{"mediaId":"$front","view":"FRONT"},{"mediaId":"$back","view":"BACK"}]}""",
        )

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals("W/\"4\"", response.headers[HttpHeaders.ETag])
        val added = response.json()
        assertEquals("black", added.getValue("color").jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals(8, added.getValue("stockOnHand").jsonPrimitive.int)
        assertEquals(listOf(front, back), added.getValue("media").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })

        val shopColor = page(bag.id).getValue("variants").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == added.getValue("id").jsonPrimitive.content }
        assertEquals(2, shopColor.getValue("media").jsonArray.size)
        assertEquals("IN_STOCK", shopColor.getValue("stock").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(1, rows("VARIANT_ADDED", added.getValue("id").jsonPrimitive.content.toLong()).size)
    }

    @Test
    fun `a color already on the product, even inactive, is 409 COLOR_ALREADY_ON_PRODUCT - another product's code is 409 DUPLICATE_SKU`() = stack.app {
        val bag = product(listOf("lilac", "black"))
        catalog.change(bag.id) { it.deactivateVariant(it.variants[1].id, catalog.staff, catalog.now) }
        val other = product(listOf("cream"))

        val again = send("POST", "/admin/products/${bag.id.value}/variants", """{"colorKey":"black"}""")
        val takenCode = send("POST", "/admin/products/${bag.id.value}/variants", """{"colorKey":"cream","sku":"${other.variants.single().sku.value}"}""")

        assertEquals(HttpStatusCode.Conflict to "COLOR_ALREADY_ON_PRODUCT", again.status to again.code())
        assertEquals(HttpStatusCode.Conflict to "DUPLICATE_SKU", takenCode.status to takenCode.code())
    }

    @Test
    fun `a color added with isActive false is on the product but not in the shop`() = stack.app {
        val bag = product(published = true)

        val response = send("POST", "/admin/products/${bag.id.value}/variants", """{"colorKey":"cream","isActive":false}""")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals(false, response.json().getValue("isActive").jsonPrimitive.content.toBoolean())
        val page = page(bag.id)
        assertEquals(listOf("lilac"), page.colorKeys("variants"))
        assertEquals(listOf("lilac", "cream"), page.colorKeys("adminVariants"))
    }

    // ============ editing ============

    @Test
    fun `PUT an inactive color with isActive and isDefault makes it active and the default - the old default is not`() = stack.app {
        val bag = product(listOf("lilac", "black"), published = true)
        val black = bag.variants[1]
        catalog.change(bag.id) { it.deactivateVariant(black.id, catalog.staff, catalog.now) }

        val response = send("PUT", "/admin/products/${bag.id.value}/variants/${black.id.value}", """{"colorKey":"black","isActive":true,"isDefault":true}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val page = page(bag.id)
        assertEquals(true to true, page.adminVariant("black").let { it.getValue("isActive").jsonPrimitive.content.toBoolean() to it.getValue("isDefault").jsonPrimitive.content.toBoolean() })
        assertEquals(false, page.adminVariant("lilac").getValue("isDefault").jsonPrimitive.content.toBoolean())
        assertEquals("black", page.getValue("defaultColorKey").jsonPrimitive.content)
        assertEquals(
            mapOf("isDefault" to ("false" to "true"), "isActive" to ("false" to "true")),
            changedFields(rows("VARIANT_UPDATED", black.id.value).single()),
        )
    }

    @Test
    fun `PUT with another key is 400 COLOR_KEY_IMMUTABLE - with stockQuantity 99 the stock stays`() = stack.app {
        val bag = product()
        val lilac = bag.variants.single()
        catalog.stock(lilac.id) { it.adjust(StockDelta.constructFromPersisted(4), AdjustmentReason.RESTOCK, null, catalog.staff, catalog.now) }

        val otherKey = send("PUT", "/admin/products/${bag.id.value}/variants/${lilac.id.value}", """{"colorKey":"black"}""")
        val withStock = send("PUT", "/admin/products/${bag.id.value}/variants/${lilac.id.value}", """{"colorKey":"lilac","stockQuantity":99,"priceOverride":1500000}""")

        assertEquals(listOf("colorKey COLOR_KEY_IMMUTABLE"), otherKey.errors())
        assertEquals(HttpStatusCode.OK, withStock.status, withStock.bodyAsText())
        assertEquals(4, withStock.json().getValue("stockOnHand").jsonPrimitive.int)
        assertEquals(1_500_000, withStock.json().getValue("price").jsonObject.getValue("price").jsonPrimitive.int)
        assertEquals(mapOf("priceOverride" to (null to "1500000")), changedFields(rows("VARIANT_UPDATED", lilac.id.value).single()))
    }

    @Test
    fun `the same values again are 200 with no event and no row`() = stack.app {
        val bag = product()
        val lilac = bag.variants.single()

        val response = send("PUT", "/admin/products/${bag.id.value}/variants/${lilac.id.value}", """{"colorKey":"lilac","sortOrder":0}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(bag.version, catalog.read(bag.id).version)
        assertTrue(rows("VARIANT_UPDATED", lilac.id.value).isEmpty())
    }

    @Test
    fun `the last active color of a published product can't go - 422 LAST_ACTIVE_VARIANT - of a draft it can`() = stack.app {
        val published = product(published = true)
        val draft = product()

        val refused = send("DELETE", "/admin/products/${published.id.value}/variants/${published.variants.single().id.value}")
        val byPut = send("PUT", "/admin/products/${published.id.value}/variants/${published.variants.single().id.value}", """{"colorKey":"lilac","isActive":false}""")
        val allowed = send("DELETE", "/admin/products/${draft.id.value}/variants/${draft.variants.single().id.value}")

        assertEquals(HttpStatusCode.UnprocessableEntity to "LAST_ACTIVE_VARIANT", refused.status to refused.code())
        assertEquals(HttpStatusCode.UnprocessableEntity to "LAST_ACTIVE_VARIANT", byPut.status to byPut.code())
        assertEquals(HttpStatusCode.NoContent, allowed.status)
        assertEquals(1, rows("VARIANT_DEACTIVATED", draft.variants.single().id.value).size)
    }

    @Test
    fun `DELETE of a color already inactive is 204 with no event and no row`() = stack.app {
        val bag = product(listOf("lilac", "black"))
        val black = bag.variants[1]
        send("DELETE", "/admin/products/${bag.id.value}/variants/${black.id.value}")
        val version = catalog.read(bag.id).version

        val again = send("DELETE", "/admin/products/${bag.id.value}/variants/${black.id.value}")

        assertEquals(HttpStatusCode.NoContent, again.status)
        assertEquals(version, catalog.read(bag.id).version)
        assertEquals(1, rows("VARIANT_DEACTIVATED", black.id.value).size)
    }

    @Test
    fun `a color of another product in the path is 404 VARIANT_NOT_FOUND - and so is a malformed id`() = stack.app {
        val bag = product()
        val other = product(listOf("black"))

        val foreign = send("PUT", "/admin/products/${bag.id.value}/variants/${other.variants.single().id.value}", """{"colorKey":"black"}""")
        val malformed = send("DELETE", "/admin/products/${bag.id.value}/variants/abc")

        assertEquals(HttpStatusCode.NotFound to "VARIANT_NOT_FOUND", foreign.status to foreign.code())
        assertEquals(HttpStatusCode.NotFound to "VARIANT_NOT_FOUND", malformed.status to malformed.code())
    }

    @Test
    fun `an OPERATOR is 403 on the color writes`() = stack.app {
        val bag = product()
        val operator = stack.callerWith(Role.OPERATOR)

        val response = client.post("$API_V1/admin/products/${bag.id.value}/variants") {
            header(HttpHeaders.Authorization, operator.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"colorKey":"black"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(1, catalog.read(bag.id).variants.size)
    }
}
