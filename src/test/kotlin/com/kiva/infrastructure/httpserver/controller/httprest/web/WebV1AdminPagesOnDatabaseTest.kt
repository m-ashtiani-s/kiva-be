package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.content.usecase.command.SavePageUc
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.page.repository.StaticPageRepository
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.StaticPageRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.StaticPageTbl
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
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Static pages in the panel and on the site (`/admin/pages*`, `/pages/{slug}`; step 5.1) on kiva_test. */
class WebV1AdminPagesOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-21T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val db = TestDatabase.database

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        // No other test writes pages.
        transaction(db) { StaticPageTbl.deleteAll() }
    }

    private val editor by lazy { panel.callerWith(Role.CONTENT_EDITOR) }

    private suspend fun ApplicationTestBuilder.save(slug: String, body: String, caller: PanelTestStack.Caller = editor): HttpResponse =
        client.put("$API_V1/admin/pages/$slug") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.list(caller: PanelTestStack.Caller = editor): HttpResponse =
        client.get("$API_V1/admin/pages") { header(HttpHeaders.Authorization, caller.bearer) }

    private suspend fun ApplicationTestBuilder.page(slug: String, ifNoneMatch: String? = null): HttpResponse =
        client.get("$API_V1/pages/$slug") { ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) } }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun body(title: String = "قوانین و حریم خصوصی", content: String = "<p>متن قوانین</p>", extra: String = "") =
        """{"title":${JsonPrimitive(title)},"content":${JsonPrimitive(content)}$extra}"""

    /** The PAGE_SAVED rows of [caller], oldest first, as `slug changes`. */
    private fun savedRows(caller: PanelTestStack.Caller): List<Pair<String, JsonArray?>> = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.actorId eq caller.userId) and (AuditLogTbl.action eq "PAGE_SAVED") }
            .orderBy(AuditLogTbl.id).map { it[AuditLogTbl.entityId] to it[AuditLogTbl.changes] }
    }

    @Test
    fun `PUT makes a page and rewrites it - both 200 with the page as the site shows it, one PAGE_SAVED row each, never the text`() = panel.app {
        val made = save("terms", body())
        assertEquals(HttpStatusCode.OK, made.status, made.bodyAsText())
        val page = made.json()
        assertEquals(ContractSchemas.propertiesOf("StaticPage"), page.keys)
        assertEquals(listOf("terms", "قوانین و حریم خصوصی", "<p>متن قوانین</p>", clock.now.toString()), listOf("slug", "title", "content", "updatedAt").map { page.text(it) })
        val seo = page.getValue("seo").jsonObject
        assertEquals(
            listOf("قوانین و حریم خصوصی | کیوا", null, "${PanelTestStack.SITE_URL}/pages/terms", null, "false"),
            listOf("title", "description", "canonicalUrl", "ogImageUrl", "noIndex").map { seo.text(it) },
        )

        clock.advance(Duration.ofMinutes(5))
        val rewritten = save("terms", body(content = "<p>متن تازه</p>", extra = ""","seo":{"title":"قوانین کیوا","noIndex":true}"""))
        assertEquals(HttpStatusCode.OK, rewritten.status, rewritten.bodyAsText())

        val shown = page("terms").json()
        assertEquals(listOf("<p>متن تازه</p>", clock.now.toString()), listOf(shown.text("content"), shown.text("updatedAt")))
        assertEquals(listOf("قوانین کیوا", "true"), listOf(shown.getValue("seo").jsonObject.text("title"), shown.getValue("seo").jsonObject.text("noIndex")))
        val rows = savedRows(editor)
        assertEquals(listOf("terms", "terms"), rows.map { it.first })
        assertFalse(rows.any { "متن" in it.second.toString() }, "the page's text is never in the log: $rows")
        val changes = rows.last().second!!.map { it.jsonObject }.associate { it.text("field") to (it.text("from") to it.text("to")) }
        assertEquals(setOf("content", "seo"), changes.keys)
        assertEquals(null to null, changes["content"])
    }

    @Test
    fun `the same page saved again is 200 with nothing written and no row`() = panel.app {
        save("about", body())
        clock.advance(Duration.ofMinutes(1))

        val again = save("about", body())

        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(Instant.parse("2026-10-21T08:00:00Z").toString(), again.json().text("updatedAt"))
        assertEquals(1, savedRows(editor).size)
    }

    @Test
    fun `script, on-handlers and javascript links go - a path of the site, a place on the page and eNamad stay, a protocol-relative link loses its href`() = panel.app {
        val dirty = """<p>قوانین<script>alert(1)</script> <b onclick="steal()">مهم</b> <a href="javascript:alert(1)">بد</a> """ +
            """<a href="/faq#return">بازگشت</a> <a href="#top">بالا</a> <a href="https://enamad.ir/x?id=1">نماد</a> <a href="//evil.example">بد</a></p>"""

        save("terms", body(content = dirty))

        val kept = page("terms").json().text("content")!!
        assertFalse(listOf("script", "alert", "onclick", "steal", "javascript:", "evil.example").any { it in kept }, kept)
        assertTrue(listOf("href=\"/faq#return\"", "href=\"#top\"", "href=\"https://enamad.ir/x?id=1\"").all { it in kept }, kept)
    }

    @Test
    fun `another slug in the body is SLUG_IMMUTABLE, the same one is fine - a path that can't be a slug is SLUG_INVALID`() = panel.app {
        assertEquals(listOf("slug SLUG_IMMUTABLE"), save("terms", body(extra = ""","slug":"privacy"""")).fieldErrors())
        assertEquals(HttpStatusCode.OK, save("terms", body(extra = ""","slug":"terms"""")).status)
        assertEquals(listOf("slug SLUG_INVALID"), save("Terms_1", body()).fieldErrors())
    }

    @Test
    fun `no title and content that is only a script - both at once, each on its field, and nothing written`() = panel.app {
        val refused = save("terms", body(title = " ", content = "<script>alert(1)</script>"))

        assertEquals(listOf("title PAGE_TITLE_REQUIRED", "content PAGE_CONTENT_REQUIRED"), refused.fieldErrors())
        assertEquals(HttpStatusCode.NotFound, page("terms").status)
    }

    @Test
    fun `a page nobody wrote, and a slug that can't be one, are 404 - nothing is made up`() = panel.app {
        assertEquals(HttpStatusCode.NotFound, page("privacy").status)
        assertEquals(HttpStatusCode.NotFound, page("Not_A_Slug").status)
    }

    @Test
    fun `the panel's list is every page by slug, without the content`() = panel.app {
        save("terms", body(title = "قوانین"))
        save("about", body(title = "درباره‌ی ما"))

        val response = list()

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val rows = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(listOf("about" to "درباره‌ی ما", "terms" to "قوانین"), rows.map { it.text("slug") to it.text("title") })
        assertEquals(ContractSchemas.propertiesOf("AdminStaticPageSummary"), rows.first().keys)
    }

    @Test
    fun `the site's page is cached - its ETag again is 304, a new save gives another`() = panel.app {
        save("terms", body())
        val first = page("terms")
        val etag = first.headers[HttpHeaders.ETag]!!
        assertEquals("public, max-age=300", first.headers[HttpHeaders.CacheControl])

        assertEquals(HttpStatusCode.NotModified, page("terms", ifNoneMatch = etag).status)

        save("terms", body(content = "<p>متن تازه</p>"))
        assertEquals(HttpStatusCode.OK, page("terms", ifNoneMatch = etag).status)
    }

    @Test
    fun `ADMIN and CONTENT_EDITOR may - OPERATOR and SUPPORT are 403 on the list and on saving`() = panel.app {
        assertEquals(HttpStatusCode.OK, save("terms", body(), panel.callerWith(Role.ADMIN)).status)
        listOf(Role.OPERATOR, Role.SUPPORT).forEach { role ->
            val caller = panel.callerWith(role)
            assertEquals(HttpStatusCode.Forbidden, list(caller).status, "$role")
            assertEquals(HttpStatusCode.Forbidden, save("about", body(), caller).status, "$role")
        }
        assertEquals(HttpStatusCode.NotFound, page("about").status)
    }

    @Test
    fun `two first saves of one page at the same moment, 10 times - both 200, one row, the second over the first`() {
        // Both requests are past the form and about to take the page's lock: the second waits, then reads the first's page.
        var bothIn = CountDownLatch(2)
        val pages = object : StaticPageRepository by StaticPageRepositoryImpl() {
            override fun lock(slug: Slug) {
                bothIn.countDown()
                bothIn.await(5, TimeUnit.SECONDS)
                StaticPageRepositoryImpl().lock(slug)
            }
        }
        val useCases = AdminContentUseCases(
            panel.checkPanelAccess, panel.adminContent.listPages, panel.getPage, SavePageUc(panel.transactions, pages, JsoupHtmlSanitizer, panel.auditTrail, clock),
            panel.adminContent.listFaq, panel.adminContent.saveFaq, panel.adminContent.listAnnouncements, panel.adminContent.saveAnnouncements,
        )
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureHttp(trustedProxyHops = 0)
                configureAuthentication(panel.accessTokens)
                routing { route(API_V1) { webV1AdminContent(useCases, PanelTestStack.SITE_URL) } }
            }
            repeat(10) { round ->
                bothIn = CountDownLatch(2)
                val slug = "race-$round"

                val statuses = coroutineScope {
                    listOf("<p>یک</p>", "<p>دو</p>").map { content -> async { save(slug, body(content = content)).status } }.awaitAll()
                }

                assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK), statuses, "round $round")
                assertEquals(1L, transaction(db) { StaticPageTbl.selectAll().where { StaticPageTbl.slug eq slug }.count() }, "round $round")
            }
        }
    }
}
