package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.json.JsonArray
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
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Banners over kiva_test (roadmap step 11.3): the panel's form (a BANNER picture always, a button whole, a safe link),
 * which banners the shop shows (their window, their order, 10 at most), and the change log.
 */
class WebV1BannersOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
    private val panel = PanelTestStack(clock)

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
    }

    private val editor by lazy { panel.callerWith(Role.CONTENT_EDITOR) }

    private suspend fun ApplicationTestBuilder.upload(purpose: String): String = client.post("$API_V1/admin/media") {
        header(HttpHeaders.Authorization, editor.bearer)
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("purpose", purpose)
                    append("file", FakeMediaProcessor.photo(2000, 1000), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"banner.jpg\"") })
                },
            ),
        )
    }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.json().getValue("id").jsonPrimitive.content

    /** A new banner, or [id] replaced. */
    private suspend fun ApplicationTestBuilder.save(body: String, id: String? = null): HttpResponse {
        val form: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, editor.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (id == null) client.post("$API_V1/admin/banners", form) else client.put("$API_V1/admin/banners/$id", form)
    }

    private fun banner(image: String, title: String = "الان بخر، همه رو یکجا تحویل بگیر", extra: String = ""): String =
        """{"placement":"HOME_PROMO","title":"$title","imageMediaId":"$image","ctaLabel":"بیشتر بدونم","ctaUrl":"/faq#reserve"$extra}"""

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.array(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray

    private suspend fun HttpResponse.fieldErrors(): Set<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }.toSet()

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun auditOf(id: String): List<String> = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.entityType eq "BANNER") and (AuditLogTbl.entityId eq id) }.orderBy(AuditLogTbl.id).map { it[AuditLogTbl.action] }
    }

    @Test
    fun `made with its picture in full, replaced and deleted - each with its row - CREAM by default, no tag nor overlay when none is given`() = panel.app {
        val image = upload("BANNER")

        val made = save(banner(image))

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        val body = made.json()
        assertEquals(ContractSchemas.propertiesOf("Banner"), body.keys)
        assertEquals(listOf("CREAM", "0", "/faq#reserve"), listOf(body.text("theme"), body.text("sortOrder"), body.getValue("cta").jsonObject.text("url")))
        assertEquals(listOf(JsonNull, JsonNull), listOf(body["tag"], body["overlay"]))
        assertEquals(image, body.getValue("image").jsonObject.text("id"))
        val id = body.text("id")!!

        val replaced = save(banner(image, extra = ""","theme":"DARK","tagLabel":"رزرو ۴ روزه","overlayHighlight":"4","overlayChannel":"BALE""""), id)
        assertEquals(HttpStatusCode.OK, replaced.status, replaced.bodyAsText())
        assertEquals(listOf("DARK", "رزرو ۴ روزه", "BALE"), replaced.json().let { listOf(it.text("theme"), it.getValue("tag").jsonObject.text("label"), it.getValue("overlay").jsonObject.text("channel")) })
        assertEquals(HttpStatusCode.OK, save(banner(image, extra = ""","theme":"DARK","tagLabel":"رزرو ۴ روزه","overlayHighlight":"4","overlayChannel":"BALE""""), id).status)

        assertEquals(HttpStatusCode.NoContent, client.delete("$API_V1/admin/banners/$id") { header(HttpHeaders.Authorization, editor.bearer) }.status)
        assertEquals(HttpStatusCode.NotFound, save(banner(image), id).status)
        assertEquals(listOf("BANNER_CREATED", "BANNER_UPDATED", "BANNER_DELETED"), auditOf(id), "a save that changes nothing writes no row")
    }

    @Test
    fun `no picture 400 IMAGE_REQUIRED - a product photo 400 MEDIA_PURPOSE_MISMATCH - a label without a link 400 CTA_INCOMPLETE - javascript 400 URL_INVALID`() = panel.app {
        val noImage = save("""{"placement":"HOME_PROMO","title":"x","ctaLabel":"بیشتر"}""")
        assertEquals(setOf("imageMediaId IMAGE_REQUIRED", "ctaUrl CTA_INCOMPLETE"), noImage.fieldErrors())

        val productPhoto = upload("PRODUCT")
        assertEquals(setOf("imageMediaId MEDIA_PURPOSE_MISMATCH"), save(banner(productPhoto)).fieldErrors())

        val image = upload("BANNER")
        val script = save("""{"placement":"HOME_PROMO","title":"x","imageMediaId":"$image","ctaLabel":"بزن","ctaUrl":"javascript:alert(1)"}""")
        assertEquals(setOf("ctaUrl URL_INVALID"), script.fieldErrors())
        val bad = save("""{"placement":"SIDE","theme":"RED","title":"","imageMediaId":"$image","startsAt":"2026-11-02T00:00:00Z","endsAt":"2026-11-01T00:00:00Z"}""")
        assertEquals(setOf("placement BANNER_PLACEMENT_INVALID", "theme BANNER_THEME_INVALID", "title FIELD_REQUIRED", "endsAt DATE_RANGE_INVALID"), bad.fieldErrors())
    }

    @Test
    fun `the shop - only those in their window, by sortOrder - tomorrow's only in the panel - no placement 400`() = panel.app {
        val image = upload("BANNER")
        save(banner(image, title = "دوم", extra = ""","sortOrder":2"""))
        save(banner(image, title = "اول", extra = ""","sortOrder":1"""))
        save(banner(image, title = "فردا", extra = ""","startsAt":"2026-10-21T08:00:00Z""""))
        save(banner(image, title = "تمام‌شده", extra = ""","endsAt":"2026-10-20T08:00:00Z""""))
        save("""{"placement":"SHOP_TOP","title":"جای دیگه","imageMediaId":"$image"}""")

        val shown = client.get("$API_V1/banners?placement=HOME_PROMO").array().map { it.jsonObject.text("title") }
        assertEquals(listOf("اول", "دوم"), shown)
        val panelList = client.get("$API_V1/admin/banners") { header(HttpHeaders.Authorization, editor.bearer) }.array().map { it.jsonObject.text("title") }
        assertTrue(panelList.containsAll(listOf("فردا", "تمام‌شده", "جای دیگه")), "$panelList")

        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1/banners").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1/banners?placement=NOWHERE").status)
    }

    @Test
    fun `the banners are ADMIN and CONTENT_EDITOR's - OPERATOR 403`() = panel.app {
        assertEquals(HttpStatusCode.OK, client.get("$API_V1/admin/banners") { header(HttpHeaders.Authorization, panel.callerWith(Role.ADMIN).bearer) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("$API_V1/admin/banners") { header(HttpHeaders.Authorization, panel.callerWith(Role.OPERATOR).bearer) }.status)
    }
}
