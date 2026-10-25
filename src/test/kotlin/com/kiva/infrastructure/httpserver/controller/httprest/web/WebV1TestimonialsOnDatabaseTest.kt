package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ColorTbl
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Testimonials over kiva_test (roadmap step 11.4): the panel's form (the product's own colour, the limits, nothing from
 * the future), the shop's list (only the published ones, in the panel's order), a colour in use, and the change log.
 */
class WebV1TestimonialsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
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
        transaction(db) { ColorTbl.deleteWhere { ColorTbl.colorKey like "tst%" } }
    }

    private val editor by lazy { panel.callerWith(Role.CONTENT_EDITOR) }

    private fun product(vararg colors: String): Product {
        val product = catalog.newProduct(colors.map { NewColor(it) }, at = clock.now)
        catalog.change(product.id) { it.publish(catalog.staff, clock.now) }
        return product
    }

    private fun body(product: Product, extra: String = "", name: String = "سارا"): String =
        """{"customerName":"$name","city":"تهران","source":"TELEGRAM","message":"کیف امروز رسید. دقیقاً همونیه که توی عکس دیدم!","messageTime":"10:12","productId":${product.id.value}$extra}"""

    private suspend fun ApplicationTestBuilder.save(body: String, id: String? = null): HttpResponse {
        val form: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, editor.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (id == null) client.post("$API_V1/admin/testimonials", form) else client.put("$API_V1/admin/testimonials/$id", form)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.fieldErrors(): Set<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }.toSet()

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun auditOf(id: String): List<Pair<String, String?>> = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.entityType eq "TESTIMONIAL") and (AuditLogTbl.entityId eq id) }.orderBy(AuditLogTbl.id)
            .map { it[AuditLogTbl.action] to it[AuditLogTbl.changes]?.toString() }
    }

    @Test
    fun `made, replaced and deleted with their rows - the change log keeps neither the name nor the message - 5 stars and published by default`() = panel.app {
        val bag = product("lilac", "black")

        val made = save(body(bag, extra = ""","colorKey":"black""""))

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        val testimonial = made.json()
        assertEquals(ContractSchemas.propertiesOf("Testimonial") + setOf("isPublished", "sortOrder", "screenshotMediaId", "createdAt"), testimonial.keys)
        assertEquals(listOf("س", "تلگرام", "5", "true", "black"), listOf(testimonial.text("customerInitial"), testimonial.text("sourceName"), testimonial.text("rating"), testimonial.text("isPublished"), testimonial.getValue("color").jsonObject.text("key")))
        assertEquals(listOf(JsonNull, JsonNull), listOf(testimonial["screenshotUrl"], testimonial["screenshotMediaId"]))
        val id = testimonial.text("id")!!

        assertEquals(HttpStatusCode.OK, save(body(bag, name = "مریم", extra = ""","message":"عالی بود، ممنون","rating":4"""), id).status)
        assertEquals(HttpStatusCode.NoContent, client.delete("$API_V1/admin/testimonials/$id") { header(HttpHeaders.Authorization, editor.bearer) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("$API_V1/admin/testimonials/$id") { header(HttpHeaders.Authorization, editor.bearer) }.status)
        val rows = auditOf(id)
        assertEquals(listOf("TESTIMONIAL_CREATED", "TESTIMONIAL_UPDATED", "TESTIMONIAL_DELETED"), rows.map { it.first })
        val changes = rows[1].second.orEmpty()
        listOf("سارا", "مریم", "عالی بود").forEach { assertFalse(changes.contains(it), "the change log holds «$it»: $changes") }
    }

    @Test
    fun `another product's colour 400 COLOR_NOT_FOUND - 501 characters 400 - from the future 400 DATE_IN_FUTURE - an archived product 400`() = panel.app {
        val bag = product("lilac")
        val archived = product("black")
        catalog.change(archived.id) { it.archive(catalog.staff, clock.now) }

        assertEquals(setOf("colorKey COLOR_NOT_FOUND"), save(body(bag, extra = ""","colorKey":"black"""")).fieldErrors())
        val long = "ا".repeat(501)
        val bad = save("""{"customerName":"","source":"FAX","message":"$long","messageTime":"25:00","receivedAt":"2026-10-21T00:00:00Z","rating":6,"productId":${bag.id.value}}""")
        assertEquals(
            setOf("customerName FIELD_REQUIRED", "source SOCIAL_CHANNEL_INVALID", "message TEXT_TOO_LONG", "messageTime MESSAGE_TIME_INVALID", "receivedAt DATE_IN_FUTURE", "rating RATING_INVALID"),
            bad.fieldErrors(),
        )
        assertEquals(setOf("productId PRODUCT_NOT_FOUND"), save(body(archived)).fieldErrors())
    }

    @Test
    fun `the shop - only the published ones, by sortOrder then the latest received, none received last`() = panel.app {
        val bag = product("lilac")
        save(body(bag, name = "بی‌تاریخ"))
        save(body(bag, name = "قدیمی", extra = ""","receivedAt":"2026-10-01T00:00:00Z""""))
        save(body(bag, name = "تازه", extra = ""","receivedAt":"2026-10-10T00:00:00Z""""))
        save(body(bag, name = "اول", extra = ""","sortOrder":-1"""))
        save(body(bag, name = "پنهان", extra = ""","isPublished":false"""))

        val shop = client.get("$API_V1/testimonials").json()
        assertEquals(ContractSchemas.propertiesOf("TestimonialListResponse"), shop.keys)
        assertEquals(listOf("اول", "تازه", "قدیمی", "بی‌تاریخ"), shop.getValue("items").jsonArray.map { it.jsonObject.text("customerName") })
        val hidden = client.get("$API_V1/admin/testimonials?published=false") { header(HttpHeaders.Authorization, editor.bearer) }.json()
        assertEquals(listOf("پنهان"), hidden.getValue("items").jsonArray.map { it.jsonObject.text("customerName") })
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1/admin/testimonials?published=maybe") { header(HttpHeaders.Authorization, editor.bearer) }.status)
    }

    @Test
    fun `a colour a testimonial names can't leave the palette - 409 IN_USE with the count`() = panel.app {
        val created = client.post("$API_V1/admin/colors") {
            header(HttpHeaders.Authorization, panel.callerWith(Role.ADMIN).bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"key":"tst-rose","name":"رز آزمایشی","hex":"#E8A0B0"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val bag = product("tst-rose")
        assertEquals(HttpStatusCode.Created, save(body(bag, extra = ""","colorKey":"tst-rose"""")).status)
        catalog.deleteAll()

        val removal = client.delete("$API_V1/admin/colors/tst-rose") { header(HttpHeaders.Authorization, panel.callerWith(Role.ADMIN).bearer) }
        assertEquals(HttpStatusCode.Conflict, removal.status)
        assertEquals(listOf("IN_USE", "1"), removal.json().let { listOf(it.text("code"), it.getValue("meta").jsonObject.text("testimonialCount")) })
        panel.deleteWhatWasStored()
        assertEquals(HttpStatusCode.NoContent, client.delete("$API_V1/admin/colors/tst-rose") { header(HttpHeaders.Authorization, panel.callerWith(Role.ADMIN).bearer) }.status)
    }

    @Test
    fun `the testimonials are ADMIN and CONTENT_EDITOR's - SUPPORT 403`() = panel.app {
        assertEquals(HttpStatusCode.OK, client.get("$API_V1/admin/testimonials") { header(HttpHeaders.Authorization, panel.callerWith(Role.ADMIN).bearer) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("$API_V1/admin/testimonials") { header(HttpHeaders.Authorization, panel.callerWith(Role.SUPPORT).bearer) }.status)
    }
}
