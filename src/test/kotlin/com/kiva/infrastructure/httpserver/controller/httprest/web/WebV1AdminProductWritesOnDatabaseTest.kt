package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.builtins.serializer
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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `POST /admin/products` and `PUT /admin/products/{productId}` over kiva_test (step 1.5), with the panel's real stack:
 * uploads, the event store, the read model, the stock and the change log.
 */
class WebV1AdminProductWritesOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-04-02T09:00:00Z"))
    private val stack = PanelTestStack(clock, FakeMediaProcessor())
    private val db = stack.db

    private val categoryId = transaction(db) { CategoryRepositoryImpl().findBySlug(Slug.constructFromPersisted("shoulder"))!!.id.value }
    private val materialId = transaction(db) { MaterialRepositoryImpl().findAll().first().id.value }

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) {
            val productIds = ProductQmTbl.selectAll().where { ProductQmTbl.slug like "tstp-%" }.map { it[ProductQmTbl.productId] }
            val variantIds = ProductVariantQmTbl.selectAll().where { ProductVariantQmTbl.productId inList productIds }.map { it[ProductVariantQmTbl.variantId] }
            EventStoreTbl.deleteWhere { (aggregateName eq "PRODUCT") and (aggregateId inList productIds.map { it.toString() }) }
            EventStoreTbl.deleteWhere { (aggregateName eq "INVENTORY") and (aggregateId inList variantIds.map { it.toString() }) }
            ProductVariantQmTbl.deleteWhere { productId inList productIds }
            ProductQmTbl.deleteWhere { productId inList productIds }
        }
        stack.deleteWhatWasStored()
    }

    private val manager get() = stack.callerWith(Role.CATALOG_MANAGER)

    // ============ helpers ============

    private suspend fun ApplicationTestBuilder.send(
        method: String,
        path: String,
        body: String,
        ifMatch: String? = null,
        caller: PanelTestStack.Caller = manager,
    ): HttpResponse {
        val block: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, caller.bearer)
            if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (method == "POST") client.post("$API_V1$path", block) else client.put("$API_V1$path", block)
    }

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse =
        client.get("$API_V1$path") { header(HttpHeaders.Authorization, manager.bearer) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.errors(): List<String> =
        json()["errors"]?.jsonArray?.map { "${it.jsonObject.getValue("field").jsonPrimitive.content} ${it.jsonObject.getValue("code").jsonPrimitive.content}" }.orEmpty()
    private suspend fun HttpResponse.code(): String = json().getValue("code").jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.upload(purpose: String = "PRODUCT"): String = client.post("$API_V1/admin/media") {
        header(HttpHeaders.Authorization, manager.bearer)
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("purpose", purpose)
                    append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                },
            ),
        )
    }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.json().getValue("id").jsonPrimitive.content

    private fun quoted(text: String) = Json.encodeToString(String.serializer(), text)

    /** A product form; [variants] and [extra] are raw JSON. */
    private fun form(
        slug: String = "tstp-mahak",
        name: String = "کیف دوشی ماهک",
        price: Long = 1_890_000,
        description: String = "<p>کیف دوشی جادار</p>",
        variants: String = """[{"colorKey":"lilac"}]""",
        extra: String = "",
    ) = """
        {"name":${quoted(name)},"slug":"$slug","categoryId":$categoryId,"bagType":"HOBO","materialId":$materialId,"price":$price,
         "description":${quoted(description)},"variants":$variants$extra}
    """.trimIndent()

    private suspend fun ApplicationTestBuilder.create(body: String = form()): JsonObject =
        send("POST", "/admin/products", body).also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.json()

    private fun productEvents(productId: Long): List<String> = transaction(db) {
        EventStoreTbl.selectAll().where { (EventStoreTbl.aggregateName eq "PRODUCT") and (EventStoreTbl.aggregateId eq productId.toString()) }
            .orderBy(EventStoreTbl.sequence).map { it[EventStoreTbl.eventType] }
    }

    private fun auditRows(action: String, entityId: Long) = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.action eq action) and (AuditLogTbl.entityId eq entityId.toString()) }.toList()
    }

    private fun changedFields(row: ResultRow): Map<String, Pair<String?, String?>> =
        row[AuditLogTbl.changes].orEmpty().associate {
            val change = it.jsonObject
            change.getValue("field").jsonPrimitive.content to
                (change["from"]?.takeIf { v -> v != JsonNull }?.jsonPrimitive?.content to change["to"]?.takeIf { v -> v != JsonNull }?.jsonPrimitive?.content)
        }

    // ============ creating ============

    @Test
    fun `a full product - three colors, a gallery, starting stock, ACTIVE - is 201 and in the shop with its photos and stock`() = stack.app {
        val front = upload()
        val side = upload()
        val variants = """
            [{"colorKey":"lilac","stockQuantity":12,"isDefault":true,"barcode":"6260000123456",
              "media":[{"mediaId":"$side","view":"SIDE","sortOrder":1},{"mediaId":"$front","view":"FRONT","isPrimary":true,"alt":"نمای جلو"}]},
             {"colorKey":"black","stockQuantity":5,"priceOverride":1750000},
             {"colorKey":"cream","stockQuantity":0}]
        """.trimIndent()
        val extra = ""","status":"ACTIVE","tags":["چرم","روزمره"],"seo":{"title":"ماهک | کیوا","noIndex":true},
            "specs":{"weightGrams":650,"closure":{"type":"ZIPPER"},"straps":[{"type":"SHOULDER","lengthCm":55}],"pockets":{"internalZip":1,"phone":2},
                     "occasions":[{"code":"DAILY"}],"warranty":{"months":6}}"""

        val response = send("POST", "/admin/products", form(variants = variants, extra = extra))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.json()
        val productId = body.getValue("id").jsonPrimitive.content.toLong()
        // Created, three colors, published, then three RESTOCKs on the stock's own streams (not the product's).
        assertEquals("W/\"5\"", response.headers[HttpHeaders.ETag])
        assertEquals(listOf("ProductCreated", "VariantAdded", "VariantAdded", "VariantAdded", "ProductPublished"), productEvents(productId))
        assertEquals("ACTIVE", body.getValue("status").jsonPrimitive.content)

        // The shop's colors: lilac with its gallery in order (the primary first), each address full.
        val lilac = body.getValue("variants").jsonArray.first().jsonObject
        val gallery = lilac.getValue("media").jsonArray.map { it.jsonObject }
        assertEquals(listOf(front, side), gallery.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("FRONT", "SIDE"), gallery.map { it.getValue("view").jsonPrimitive.content })
        assertTrue(gallery[0].getValue("url").jsonPrimitive.content.startsWith(PanelTestStack.MEDIA_BASE_URL + "/"), gallery[0].toString())
        assertEquals("نمای جلو", gallery[0].getValue("alt").jsonPrimitive.content)
        assertEquals(true, gallery[0].getValue("isPrimary").jsonPrimitive.content.toBoolean())

        // The panel's colors: exact stock, the barcode, and the available total of the product.
        val admin = body.getValue("adminVariants").jsonArray.map { it.jsonObject }
        assertEquals(listOf(12, 5, 0), admin.map { it.getValue("stockOnHand").jsonPrimitive.int })
        assertEquals("6260000123456", admin[0].getValue("barcode").jsonPrimitive.content)
        assertEquals(17, transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.productId eq productId }.single()[ProductQmTbl.availableTotal] })
        assertEquals(listOf("چرم", "روزمره"), body.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("SHOULDER", body.getValue("specs").jsonObject.getValue("straps").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)

        // One RESTOCK per color with stock, in the stock's history.
        val variantIds = admin.map { it.getValue("id").jsonPrimitive.content }
        val restocks = transaction(db) {
            EventStoreTbl.selectAll().where { (EventStoreTbl.aggregateName eq "INVENTORY") and (EventStoreTbl.aggregateId inList variantIds) }
                .map { it[EventStoreTbl.aggregateId] to it[EventStoreTbl.eventType] to it[EventStoreTbl.payload] }
        }
        assertEquals(listOf(variantIds[0], variantIds[1]).sorted(), restocks.map { it.first.first }.sorted())
        assertTrue(restocks.all { it.first.second == "StockAdjusted" && "\"RESTOCK\"" in it.second }, restocks.toString())

        // The files are marked used, and one change log row.
        assertTrue(transaction(db) { stack.mediaRepository.findAll(listOf(front, side).map(::mediaIdOf)) }.all { it.isAttached })
        assertEquals(1, auditRows("PRODUCT_CREATED", productId).size)
    }

    @Test
    fun `ten shape mistakes come back together in one 400, each under its full path`() = stack.app {
        val variants = """
            [{"colorKey":"lilac","stockQuantity":-1},
             {"colorKey":"black","barcode":"x","media":[{"mediaId":"nope","view":"FRONT"}]}]
        """.trimIndent()
        val extra = ""","maxPerOrder":21,"tags":["${"ب".repeat(31)}"],"seo":{"title":"${"ع".repeat(71)}"},
            "specs":{"weightGrams":0,"straps":[{"lengthCm":250}],"pockets":{"phone":21}}"""

        val response = send("POST", "/admin/products", form(slug = "Bad Slug", variants = variants, extra = extra))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VALIDATION_ERROR", response.code())
        assertEquals(
            listOf(
                "slug SLUG_INVALID",
                "specs.straps.0.lengthCm STRAP_LENGTH_INVALID",
                "specs.pockets.phone POCKET_COUNT_INVALID",
                "specs.weightGrams WEIGHT_INVALID",
                "tags.0 TAG_INVALID",
                "maxPerOrder MAX_PER_ORDER_INVALID",
                "seo.title SEO_TITLE_TOO_LONG",
                "variants.0.stockQuantity STOCK_QUANTITY_INVALID",
                "variants.1.barcode BARCODE_INVALID",
                "variants.1.media.0.mediaId MEDIA_ID_INVALID",
            ),
            response.errors(),
        )
    }

    @Test
    fun `a pre-shipment photo, two primaries and 21 items are each refused on the gallery`() = stack.app {
        val preShipment = upload("PRE_SHIPMENT")
        val photo = upload()
        val other = upload()

        val wrongPurpose = send("POST", "/admin/products", form(variants = """[{"colorKey":"lilac","media":[{"mediaId":"$preShipment","view":"FRONT"}]}]"""))
        val twoPrimaries = send(
            "POST", "/admin/products",
            form(variants = """[{"colorKey":"lilac","media":[{"mediaId":"$photo","view":"FRONT","isPrimary":true},{"mediaId":"$other","view":"BACK","isPrimary":true}]}]"""),
        )
        val many = (1..21).joinToString(",") { """{"mediaId":"med_${it.toString().padStart(22, '0')}","view":"FRONT"}""" }
        val tooMany = send("POST", "/admin/products", form(variants = """[{"colorKey":"lilac","media":[$many]}]"""))

        assertEquals(listOf("variants.0.media.0.mediaId MEDIA_PURPOSE_MISMATCH"), wrongPurpose.errors())
        assertEquals(listOf("variants.0.media.1.isPrimary MEDIA_PRIMARY_DUPLICATE"), twoPrimaries.errors())
        assertEquals(listOf("variants.0.media MEDIA_TOO_MANY"), tooMany.errors())
        assertEquals(0L, transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.slug like "tstp-%" }.count() })
    }

    @Test
    fun `a description is kept without script, on-handlers or javascript links - a full https link stays`() = stack.app {
        val dirty = """<p>کیف<script>alert(1)</script> <b onclick="steal()">پررنگ</b> <a href="javascript:alert(1)">بد</a> <a href="https://kivashop.ir/faq">سؤال‌ها</a></p>"""

        val body = create(form(description = dirty))

        val kept = body.getValue("description").jsonPrimitive.content
        assertFalse("script" in kept || "alert" in kept, kept)
        assertFalse("onclick" in kept || "steal" in kept, kept)
        assertFalse("javascript:" in kept, kept)
        // Relative links (/faq#reserve) come with step 5.1 (its decision 6); until then only full addresses keep their href.
        assertTrue("href=\"https://kivashop.ir/faq\"" in kept, kept)
    }

    // ============ editing ============

    @Test
    fun `PUT with the current If-Match is 200 and one step up - a stale one is 412 with nothing saved - none is 200`() = stack.app {
        val created = create()
        val id = created.getValue("id").jsonPrimitive.content.toLong()
        val etag = created.getValue("etag").jsonPrimitive.content

        val current = send("PUT", "/admin/products/$id", form(name = "کیف ماهک"), ifMatch = etag)
        assertEquals(HttpStatusCode.OK, current.status, current.bodyAsText())
        assertEquals("W/\"3\"", current.headers[HttpHeaders.ETag])
        assertEquals("کیف ماهک", current.json().getValue("name").jsonPrimitive.content)

        val stale = send("PUT", "/admin/products/$id", form(name = "کیف دیگر"), ifMatch = etag)
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
        assertEquals("CHANGED_BY_SOMEONE_ELSE", stale.code())
        assertEquals("W/\"3\"", stale.json().getValue("meta").jsonObject.getValue("currentEtag").jsonPrimitive.content)
        assertEquals(3, productEvents(id).size)

        val unchecked = send("PUT", "/admin/products/$id", form(name = "کیف دیگر"))
        assertEquals(HttpStatusCode.OK, unchecked.status)
        assertEquals("W/\"4\"", unchecked.headers[HttpHeaders.ETag])

        val badShape = send("PUT", "/admin/products/$id", form(), ifMatch = "version-4")
        assertEquals(listOf("If-Match IF_MATCH_INVALID"), badShape.errors())
    }

    @Test
    fun `PUT never changes the slug, the status or the colors - a new slug is 400 SLUG_IMMUTABLE`() = stack.app {
        val id = create().getValue("id").jsonPrimitive.content.toLong()

        val otherSlug = send("PUT", "/admin/products/$id", form(slug = "tstp-other"))
        val ignored = send("PUT", "/admin/products/$id", form(variants = """[{"colorKey":"black"}]""", extra = ""","status":"ARCHIVED""""))

        assertEquals(listOf("slug SLUG_IMMUTABLE"), otherSlug.errors())
        assertEquals(HttpStatusCode.OK, ignored.status, ignored.bodyAsText())
        assertEquals("DRAFT", ignored.json().getValue("status").jsonPrimitive.content)
        assertEquals(listOf("lilac"), ignored.json().getValue("adminVariants").jsonArray.map { it.jsonObject.getValue("color").jsonObject.getValue("key").jsonPrimitive.content })
        assertEquals(2, productEvents(id).size)
    }

    @Test
    fun `a price change alone logs PRODUCT_PRICE_CHANGED - price and name log PRODUCT_UPDATED with both`() = stack.app {
        val id = create().getValue("id").jsonPrimitive.content.toLong()

        send("PUT", "/admin/products/$id", form(price = 1_750_000))
        send("PUT", "/admin/products/$id", form(price = 1_690_000, name = "کیف ماهک"))

        assertEquals(mapOf("price" to ("1890000" to "1750000")), changedFields(auditRows("PRODUCT_PRICE_CHANGED", id).single()))
        assertEquals(
            mapOf("name" to ("کیف دوشی ماهک" to "کیف ماهک"), "price" to ("1750000" to "1690000")),
            changedFields(auditRows("PRODUCT_UPDATED", id).single()),
        )
    }

    @Test
    fun `PUT on an archived product is 422 PRODUCT_ARCHIVED - on one nobody has 404`() = stack.app {
        val id = create(form(extra = ""","status":"ARCHIVED"""")).getValue("id").jsonPrimitive.content.toLong()

        val archived = send("PUT", "/admin/products/$id", form(name = "کیف ماهک"))
        val missing = send("PUT", "/admin/products/987654321", form())

        assertEquals(HttpStatusCode.UnprocessableEntity, archived.status)
        assertEquals("PRODUCT_ARCHIVED", archived.code())
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("PRODUCT_NOT_FOUND", missing.code())
    }

    @Test
    fun `an OPERATOR is 403 on both writes, and nothing is stored`() = stack.app {
        val operator = stack.callerWith(Role.OPERATOR)

        assertEquals(HttpStatusCode.Forbidden, send("POST", "/admin/products", form(), caller = operator).status)
        assertEquals(HttpStatusCode.Forbidden, send("PUT", "/admin/products/1", form(), caller = operator).status)
        assertEquals(0L, transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.slug like "tstp-%" }.count() })
    }

    private fun mediaIdOf(raw: String) = MediaId.constructFromPersisted(raw)
}
