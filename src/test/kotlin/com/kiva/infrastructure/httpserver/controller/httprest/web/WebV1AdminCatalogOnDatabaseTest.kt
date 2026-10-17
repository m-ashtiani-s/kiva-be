package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.repository.ProductFilter
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.pagination.Pageable
import com.kiva.common.text.normalizePersian
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.orFail
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ColorTbl
import com.kiva.infrastructure.persistence.exposed.tbl.MaterialTbl
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
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
import kotlin.test.assertTrue

/**
 * The panel's categories, palette and materials over kiva_test (step 1.3), through the routes with the panel's real
 * stack. Every category, color and material a test makes has a slug or key starting with `tst`; they, and the products
 * of StoredCatalog, are removed after each test. Change log rows can't be removed, so assertions read only the rows of
 * this test's own staff.
 */
class WebV1AdminCatalogOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-04-01T09:00:00Z"))
    private val stack = PanelTestStack(clock, FakeMediaProcessor())
    private val catalog = StoredCatalog()
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() {
        catalog.deleteAll()
        transaction(db) {
            CategoryTbl.deleteWhere { CategoryTbl.slug like "tst%" }
            MaterialTbl.deleteWhere { MaterialTbl.slug like "tst%" }
            ColorTbl.deleteWhere { ColorTbl.colorKey like "tst%" }
        }
        stack.deleteWhatWasStored()
    }

    private val manager get() = stack.callerWith(Role.CATALOG_MANAGER)

    // ============ helpers ============

    private suspend fun ApplicationTestBuilder.send(method: String, path: String, body: String? = null, caller: PanelTestStack.Caller = manager): HttpResponse {
        val block: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, caller.bearer)
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
    private suspend fun HttpResponse.array(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray
    private suspend fun HttpResponse.errors(): List<Pair<String, String>> =
        json()["errors"]?.jsonArray?.map { it.jsonObject.getValue("field").jsonPrimitive.content to it.jsonObject.getValue("code").jsonPrimitive.content }.orEmpty()
    private suspend fun HttpResponse.code(): String = json().getValue("code").jsonPrimitive.content

    private fun category(slug: String, name: String = "زنبق", colorKey: String = "lilac", extra: String = "") =
        """{"name":"$name","slug":"$slug","iconBagType":"HOBO","iconColorKey":"$colorKey"$extra}"""

    private suspend fun ApplicationTestBuilder.newCategory(slug: String, name: String = "زنبق", colorKey: String = "lilac"): Long =
        send("POST", "/admin/categories", category(slug, name, colorKey)).also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
            .json().getValue("id").jsonPrimitive.content.toLong()

    private suspend fun ApplicationTestBuilder.newMaterial(slug: String, name: String = "کنف"): Long =
        send("POST", "/admin/materials", """{"name":"$name","slug":"$slug","family":"OTHER"}""").also { assertEquals(HttpStatusCode.Created, it.status) }
            .json().getValue("id").jsonPrimitive.content.toLong()

    private suspend fun ApplicationTestBuilder.upload(purpose: String): String = client.post("$API_V1/admin/media") {
        header(HttpHeaders.Authorization, manager.bearer)
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("purpose", purpose)
                    append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                },
            ),
        )
    }.also { assertEquals(HttpStatusCode.Created, it.status) }.json().getValue("id").jsonPrimitive.content

    /** A product in the shop in category [categoryId] (made of [materialId]) with these colors, or a draft or archived one. */
    private fun product(categoryId: Long, colors: List<String> = listOf("lilac"), materialId: Long = 4, status: String = "ACTIVE"): Product {
        val details = ProductEventSamples.fullDetails.copy(
            categoryId = CategoryId.constructFromPersisted(categoryId), materialId = MaterialId.constructFromPersisted(materialId),
        )
        val product = catalog.newProduct(colors.map { StoredCatalog.NewColor(it) }, details = details)
        return when (status) {
            "ACTIVE" -> catalog.change(product.id) { it.publish(catalog.staff, catalog.now) }
            "ARCHIVED" -> catalog.change(product.id) { it.archive(catalog.staff, catalog.now) }
            else -> product
        }
    }

    /** What `GET /products?q=` finds: the shop's own query with the search words. */
    private fun search(text: String): List<Long> = transaction(db) {
        val pageable = Pageable.makeNew(1, 60, ProductSorts.NEWEST, ProductSorts.names).orFail()
        stack.productQm.find(ProductFilter(words = text.normalizePersian().lowercase().split(' ')), pageable).items.map { it.productId.value }
    }

    private fun inCategory(slug: String): List<Long> = transaction(db) {
        val pageable = Pageable.makeNew(1, 60, ProductSorts.NEWEST, ProductSorts.names).orFail()
        stack.productQm.find(ProductFilter(categories = setOf(Slug.constructFromPersisted(slug))), pageable).items.map { it.productId.value }
    }

    private fun auditRows(actor: PanelTestStack.Caller, action: String) = transaction(db) {
        AuditLogTbl.selectAll().where { AuditLogTbl.actorId eq actor.userId }.toList().filter { it[AuditLogTbl.action] == action }
    }

    private fun changesOf(row: ResultRow): Map<String, Pair<String?, String?>> =
        row[AuditLogTbl.changes].orEmpty().associate {
            val change = it.jsonObject
            change.getValue("field").jsonPrimitive.content to
                (change["from"]?.takeIf { v -> v != JsonNull }?.jsonPrimitive?.content to change["to"]?.takeIf { v -> v != JsonNull }?.jsonPrimitive?.content)
        }

    // ============ categories ============

    @Test
    fun `a category with every field is 201 as AdminCategory, its photo marked used, and one CATEGORY_CREATED row`() = stack.app {
        val photo = upload("CATEGORY")

        val response = send(
            "POST", "/admin/categories",
            category(
                "tst-mini", "مینی",
                extra = ""","description":"کیف‌های کوچک","imageMediaId":"$photo","sortOrder":9,"showInMenu":false,"seo":{"title":"کیف مینی","noIndex":true}""",
            ),
        )

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals("tst-mini", body.getValue("slug").jsonPrimitive.content)
        assertEquals(photo, body.getValue("imageMediaId").jsonPrimitive.content)
        assertTrue(body.getValue("imageUrl").jsonPrimitive.content.matches(Regex("^${PanelTestStack.MEDIA_BASE_URL}/2036/04/$photo/w800\\.webp$")))
        assertEquals(JsonNull, body.getValue("icon").jsonObject.getValue("imageUrl"))
        assertEquals(0, body.getValue("productCount").jsonPrimitive.int)
        assertEquals(false, body.getValue("showInMenu").jsonPrimitive.content.toBoolean())
        val seo = body.getValue("seo").jsonObject
        assertEquals("کیف مینی", seo.getValue("title").jsonPrimitive.content)
        assertEquals("کیف‌های کوچک", seo.getValue("description").jsonPrimitive.content)
        assertEquals("${PanelTestStack.SITE_URL}/categories/tst-mini", seo.getValue("canonicalUrl").jsonPrimitive.content)
        assertEquals(true, seo.getValue("noIndex").jsonPrimitive.content.toBoolean())
        assertTrue(transaction(db) { stack.mediaRepository.findAll(listOf(MediaId.constructFromPersisted(photo))) }.single().isAttached)
        val row = auditRows(manager, "CATEGORY_CREATED").single()
        assertEquals("CATEGORY", row[AuditLogTbl.entityType])
        assertEquals(body.getValue("id").jsonPrimitive.content, row[AuditLogTbl.entityId])
        assertEquals("مینی", row[AuditLogTbl.entityLabel])
    }

    @Test
    fun `without iconBagType and with a name too long, both are 400 at once on their fields`() = stack.app {
        val response = send("POST", "/admin/categories", """{"name":"${"ک".repeat(101)}","slug":"tst-x","iconColorKey":"lilac"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(listOf("name" to "CATEGORY_NAME_TOO_LONG", "iconBagType" to "FIELD_REQUIRED"), response.errors())
    }

    @Test
    fun `an icon color not in the palette is 400 COLOR_NOT_FOUND on iconColorKey`() = stack.app {
        val response = send("POST", "/admin/categories", category("tst-x", colorKey = "tst-nope"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(listOf("iconColorKey" to "COLOR_NOT_FOUND"), response.errors())
    }

    @Test
    fun `a photo uploaded for a product is 400 MEDIA_PURPOSE_MISMATCH on imageMediaId - an unknown one MEDIA_NOT_FOUND`() = stack.app {
        val productPhoto = upload("PRODUCT")

        val mismatch = send("POST", "/admin/categories", category("tst-x", extra = ""","imageMediaId":"$productPhoto""""))
        val unknown = send("POST", "/admin/categories", category("tst-x", extra = ""","imageMediaId":"med_${"Z".repeat(22)}""""))

        assertEquals(listOf("imageMediaId" to "MEDIA_PURPOSE_MISMATCH"), mismatch.errors())
        assertEquals(listOf("imageMediaId" to "MEDIA_NOT_FOUND"), unknown.errors())
    }

    @Test
    fun `a slug already taken is 409 DUPLICATE_SLUG on slug`() = stack.app {
        newCategory("tst-dup")

        val response = send("POST", "/admin/categories", category("tst-dup"))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("DUPLICATE_SLUG", response.code())
    }

    @Test
    fun `two creates with one slug at the same moment, 10 times - always one 201 and one 409, never a 500`() = stack.app {
        repeat(10) { round ->
            val statuses = coroutineScope {
                List(2) { async { send("POST", "/admin/categories", category("tst-race-$round")).status } }.awaitAll()
            }
            assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Conflict), statuses.sortedBy { it.value }, "round $round")
        }
    }

    @Test
    fun `renaming a category puts its new name in its products' search text - the old name finds nothing`() = stack.app {
        val id = newCategory("tst-flower", "زنبق")
        val bag = product(id)
        assertEquals(listOf(bag.id.value), search("زنبق"))

        val response = send("PUT", "/admin/categories/$id", category("tst-flower", "نرگسانه"))

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(listOf(bag.id.value), search("نرگسانه"))
        assertEquals(emptyList(), search("زنبق"))
    }

    @Test
    fun `a new slug moves the category's products with it - the shop's filter takes the new slug`() = stack.app {
        val id = newCategory("tst-old")
        val bag = product(id)

        val response = send("PUT", "/admin/categories/$id", category("tst-new"))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("tst-new", response.json().getValue("slug").jsonPrimitive.content)
        assertEquals(listOf(bag.id.value), inCategory("tst-new"))
        assertEquals(emptyList(), inCategory("tst-old"))
    }

    @Test
    fun `an edit logs the fields that changed - the same values again log nothing`() = stack.app {
        val id = newCategory("tst-edit", "زنبق")

        send("PUT", "/admin/categories/$id", category("tst-edit", "سوسن", extra = ""","sortOrder":4"""))
        send("PUT", "/admin/categories/$id", category("tst-edit", "سوسن", extra = ""","sortOrder":4"""))

        val rows = auditRows(manager, "CATEGORY_UPDATED")
        assertEquals(1, rows.size)
        assertEquals(mapOf("name" to ("زنبق" to "سوسن"), "sortOrder" to ("0" to "4")), changesOf(rows.single()))
        assertEquals("سوسن", rows.single()[AuditLogTbl.entityLabel])
    }

    @Test
    fun `a slug another category has is 409 on PUT - an id nobody has or a malformed one is 404`() = stack.app {
        newCategory("tst-a")
        val b = newCategory("tst-b")

        assertEquals("DUPLICATE_SLUG", send("PUT", "/admin/categories/$b", category("tst-a")).code())
        assertEquals(HttpStatusCode.NotFound, send("PUT", "/admin/categories/999999", category("tst-z")).status)
        assertEquals(HttpStatusCode.NotFound, send("PUT", "/admin/categories/abc", category("tst-z")).status)
    }

    @Test
    fun `a category whose only product is archived is 409 IN_USE with productCount 1`() = stack.app {
        val id = newCategory("tst-busy")
        product(id, status = "ARCHIVED")

        val response = send("DELETE", "/admin/categories/$id")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("IN_USE", response.code())
        assertEquals(1, response.json().getValue("meta").jsonObject.getValue("productCount").jsonPrimitive.int)
    }

    @Test
    fun `an empty category is removed - 204 and one CATEGORY_DELETED row - and again it is 404`() = stack.app {
        val id = newCategory("tst-empty", "خالی")

        assertEquals(HttpStatusCode.NoContent, send("DELETE", "/admin/categories/$id").status)
        assertEquals(HttpStatusCode.NotFound, send("DELETE", "/admin/categories/$id").status)

        val row = auditRows(manager, "CATEGORY_DELETED").single()
        assertEquals("خالی", row[AuditLogTbl.entityLabel])
        assertEquals(null, row[AuditLogTbl.changes])
    }

    @Test
    fun `the panel's lists count drafts and archived products - the shop's count only those in the shop`() = stack.app {
        val categoryId = newCategory("tst-count")
        val materialId = newMaterial("tst-hemp")
        product(categoryId, materialId = materialId, status = "ACTIVE")
        product(categoryId, materialId = materialId, status = "DRAFT")
        product(categoryId, materialId = materialId, status = "ARCHIVED")

        val panelCategory = send("GET", "/admin/categories").array().single { it.jsonObject.getValue("slug").jsonPrimitive.content == "tst-count" }
        val panelMaterial = send("GET", "/admin/materials").array().single { it.jsonObject.getValue("slug").jsonPrimitive.content == "tst-hemp" }
        val shopCategory = runBlocking { ListCategoriesUc(stack.transactions, stack.categories, stack.productQm, stack.mediaRepository).execute(ListCategoriesUc.Qry) }
            .orFail().single { it.slug == "tst-count" }
        val shopMaterial = runBlocking { ListMaterialsUc(stack.transactions, stack.materials, stack.productQm).execute(ListMaterialsUc.Qry) }
            .orFail().single { it.slug == "tst-hemp" }

        assertEquals(3, panelCategory.jsonObject.getValue("productCount").jsonPrimitive.int)
        assertEquals(3, panelMaterial.jsonObject.getValue("productCount").jsonPrimitive.int)
        assertEquals(1, shopCategory.productCount)
        assertEquals(1, shopMaterial.productCount)
    }

    // ============ colors ============

    @Test
    fun `a new color is 201 with its hex uppercased, after the last color - its key again is 409 DUPLICATE_CODE`() = stack.app {
        val created = send("POST", "/admin/colors", """{"key":"tst-mint","name":"نعنایی","hex":"#a8e6cf"}""")
        val again = send("POST", "/admin/colors", """{"key":"tst-mint","name":"نعنایی","hex":"#a8e6cf"}""")

        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.json()
        assertEquals("#A8E6CF", body.getValue("hex").jsonPrimitive.content)
        assertEquals(false, body.getValue("isLight").jsonPrimitive.content.toBoolean())
        val last = transaction(db) { stack.colors.findAll().filter { it.key.value != "tst-mint" }.maxOf { it.sortOrder } }
        assertEquals(last + 1, body.getValue("sortOrder").jsonPrimitive.int)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("DUPLICATE_CODE", again.code())
        assertEquals(1, auditRows(manager, "COLOR_CREATED").size)
    }

    @Test
    fun `a PUT whose body names another key is 400 COLOR_KEY_IMMUTABLE on key - a new name reaches the search text`() = stack.app {
        send("POST", "/admin/colors", """{"key":"tst-sage","name":"مریمی","hex":"#9CAF88"}""")
        val categoryId = newCategory("tst-sagecat")
        val bag = product(categoryId, colors = listOf("tst-sage"))

        val immutable = send("PUT", "/admin/colors/tst-sage", """{"key":"tst-other","name":"مریمی","hex":"#9CAF88"}""")
        val renamed = send("PUT", "/admin/colors/tst-sage", """{"key":"tst-sage","name":"گلپر","hex":"#9CAF88"}""")

        assertEquals(listOf("key" to "COLOR_KEY_IMMUTABLE"), immutable.errors())
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals(listOf(bag.id.value), search("گلپر"))
        assertEquals(mapOf("name" to ("مریمی" to "گلپر")), changesOf(auditRows(manager, "COLOR_UPDATED").single()))
    }

    @Test
    fun `a color only on an inactive variant is 409 IN_USE with productCount 1`() = stack.app {
        send("POST", "/admin/colors", """{"key":"tst-rust","name":"زنگاری","hex":"#B7410E"}""")
        val categoryId = newCategory("tst-rustcat")
        val bag = product(categoryId, colors = listOf("lilac", "tst-rust"), status = "DRAFT")
        val rust = bag.variants.single { it.colorKey.value == "tst-rust" }
        catalog.change(bag.id) { it.deactivateVariant(rust.id, catalog.staff, catalog.now) }

        val response = send("DELETE", "/admin/colors/tst-rust")

        assertEquals(HttpStatusCode.Conflict, response.status)
        val meta = response.json().getValue("meta").jsonObject
        assertEquals(1, meta.getValue("productCount").jsonPrimitive.int)
        assertEquals(0, meta.getValue("categoryCount").jsonPrimitive.int)
        assertEquals(0, meta.getValue("testimonialCount").jsonPrimitive.int)
    }

    @Test
    fun `a color a category draws its icon in is 409 IN_USE with categoryCount 1 - an unused one goes, 204`() = stack.app {
        send("POST", "/admin/colors", """{"key":"tst-icon","name":"آیکنی","hex":"#123456"}""")
        send("POST", "/admin/colors", """{"key":"tst-free","name":"آزاد","hex":"#654321"}""")
        newCategory("tst-iconcat", colorKey = "tst-icon")

        val busy = send("DELETE", "/admin/colors/tst-icon")
        val free = send("DELETE", "/admin/colors/tst-free")

        assertEquals(1, busy.json().getValue("meta").jsonObject.getValue("categoryCount").jsonPrimitive.int)
        assertEquals(HttpStatusCode.NoContent, free.status)
        assertEquals(1, auditRows(manager, "COLOR_DELETED").size)
    }

    // ============ materials ============

    @Test
    fun `a wrong family and eleven care lines are both 400 on their fields`() = stack.app {
        val care = (1..11).joinToString(",") { "\"خط $it\"" }

        val response = send("POST", "/admin/materials", """{"name":"کنف","slug":"tst-m","family":"WOOD","careInstructions":[$care]}""")

        assertEquals(listOf("family" to "MATERIAL_FAMILY_INVALID", "careInstructions" to "CARE_INSTRUCTIONS_INVALID"), response.errors())
    }

    @Test
    fun `renaming a material puts its new name in its products' search text - a taken slug is 409`() = stack.app {
        val materialId = newMaterial("tst-jute", "کنفی")
        newMaterial("tst-taken")
        val categoryId = newCategory("tst-jutecat")
        val bag = product(categoryId, materialId = materialId)

        val renamed = send("PUT", "/admin/materials/$materialId", """{"name":"جوتی","slug":"tst-jute","family":"OTHER","description":"${"ک".repeat(300)}"}""")
        val taken = send("PUT", "/admin/materials/$materialId", """{"name":"جوتی","slug":"tst-taken","family":"OTHER"}""")

        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        assertEquals(listOf(bag.id.value), search("جوتی"))
        assertEquals(emptyList(), search("کنفی"))
        assertEquals("DUPLICATE_SLUG", taken.code())
        // The description is long text: only that it changed, never the text.
        assertEquals(mapOf("name" to ("کنفی" to "جوتی"), "description" to (null to null)), changesOf(auditRows(manager, "MATERIAL_UPDATED").single()))
    }

    @Test
    fun `a material of a draft product is 409 IN_USE - an unused one goes, 204`() = stack.app {
        val busy = newMaterial("tst-busy")
        val free = newMaterial("tst-free")
        product(newCategory("tst-mcat"), materialId = busy, status = "DRAFT")

        assertEquals(1, send("DELETE", "/admin/materials/$busy").json().getValue("meta").jsonObject.getValue("productCount").jsonPrimitive.int)
        assertEquals(HttpStatusCode.NoContent, send("DELETE", "/admin/materials/$free").status)
        assertEquals(1, auditRows(manager, "MATERIAL_DELETED").size)
    }

    // ============ roles ============

    @Test
    fun `an OPERATOR is 403 on every catalog write, and nothing is stored`() = stack.app {
        val operator = stack.callerWith(Role.OPERATOR)

        val responses = listOf(
            send("GET", "/admin/categories", caller = operator),
            send("POST", "/admin/categories", category("tst-op"), caller = operator),
            send("POST", "/admin/colors", """{"key":"tst-op","name":"x","hex":"#000000"}""", caller = operator),
            send("POST", "/admin/materials", """{"name":"x","slug":"tst-op","family":"OTHER"}""", caller = operator),
        )

        assertEquals(List(4) { HttpStatusCode.Forbidden }, responses.map { it.status })
        assertEquals(0, transaction(db) { CategoryTbl.selectAll().where { CategoryTbl.slug inList listOf("tst-op") }.count() })
    }
}
