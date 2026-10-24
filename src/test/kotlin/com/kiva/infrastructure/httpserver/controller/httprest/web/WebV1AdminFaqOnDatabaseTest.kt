package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.content.usecase.command.SaveFaqUc
import com.kiva.domain.aggregate.faq.repository.FaqRepository
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.FaqRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.FaqGroupTbl
import com.kiva.infrastructure.persistence.exposed.tbl.FaqItemTbl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The FAQ in the panel and on the site (`/admin/faq/groups`, `/faq`; step 5.2) on kiva_test. */
class WebV1AdminFaqOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-21T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val db = TestDatabase.database

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        // No other test writes the FAQ; its questions go with their groups.
        transaction(db) { FaqGroupTbl.deleteAll() }
    }

    private val editor by lazy { panel.callerWith(Role.CONTENT_EDITOR) }

    private suspend fun ApplicationTestBuilder.save(body: String, caller: PanelTestStack.Caller = editor): HttpResponse =
        client.put("$API_V1/admin/faq/groups") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.edit(caller: PanelTestStack.Caller = editor): HttpResponse =
        client.get("$API_V1/admin/faq/groups") { header(HttpHeaders.Authorization, caller.bearer) }

    private suspend fun ApplicationTestBuilder.faq(q: String? = null, ifNoneMatch: String? = null): HttpResponse =
        client.get("$API_V1/faq") {
            q?.let { parameter("q", it) }
            ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.groups(): List<JsonObject> = Json.parseToJsonElement(bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.questions(): List<JsonObject> = getValue("questions").jsonArray.map { it.jsonObject }

    private val threeGroups = """[
        {"id":"reserve","name":"رزرو ۴ روزه","icon":"timer","isSignature":true,"questions":[
            {"question":"رزرو ۴ روزه چطور کار می‌کنه؟","answer":"<p>الان کامل پرداخت کن؛ <b>همه‌ی رزروها</b> یکجا می‌رسن. <a href=\"/faq#shipping\">ارسال</a></p>"},
            {"question":"می‌تونم رزرو رو لغو کنم؟","answer":"<p>تا قبل از تغییر وضعیت سفارش، بله.</p>"}]},
        {"id":"shipping","name":"ارسال","questions":[
            {"question":"ارسال چند روز طول می‌کشه؟","answer":"<p>تیپاکس ۱ تا ۲ روز، پست ۳ تا ۵ روز.</p>"}]},
        {"id":"return","name":"بازگشت کالا","questions":[
            {"question":"<b>بازگشت</b> چطوریه؟","answer":"<p>تا ۷ روز با تماس تلفنی.</p><script>alert(1)</script>"}]}
    ]"""

    private fun auditRows(): Int = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.actorId eq editor.userId) and (AuditLogTbl.action eq "FAQ_SAVED") }.count().toInt()
    }

    @Test
    fun `a tree of three groups is saved and answered in order, each question with a new id, the answers cleaned`() = panel.app {
        val saved = save(threeGroups)

        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val groups = saved.groups()
        assertEquals(ContractSchemas.propertiesOf("FaqGroup"), groups.first().keys)
        assertEquals(ContractSchemas.propertiesOf("FaqItem"), groups.first().questions().first().keys)
        assertEquals(listOf("reserve" to "0", "shipping" to "1", "return" to "2"), groups.map { it.text("id") to it.text("sortOrder") })
        assertEquals(listOf("timer", "true"), listOf(groups[0].text("icon"), groups[0].text("isSignature")))
        val ids = groups.flatMap { group -> group.questions().map { it.text("id")!!.toLong() } }
        assertEquals(4, ids.toSet().size)
        assertEquals("<p>تا ۷ روز با تماس تلفنی.</p>", groups[2].questions().single().text("answer"))
        assertNull(groups[0].questions()[0].text("questionHighlighted"))
        assertEquals(1, auditRows())
    }

    @Test
    fun `saved again with one question less and one new - the gone one is gone, the kept one keeps its id, the new one gets a new id`() = panel.app {
        val first = save(threeGroups).groups()
        val kept = first[0].questions()[0].text("id")!!
        val gone = first[0].questions()[1].text("id")!!

        val second = save(
            """[{"id":"reserve","name":"رزرو","questions":[
                {"id":$kept,"question":"رزرو چطوره؟","answer":"<p>خوبه</p>"},
                {"question":"تازه","answer":"<p>سؤال تازه</p>"}]}]""",
        )

        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        val questions = second.groups().single().questions()
        assertEquals(kept, questions[0].text("id"))
        assertTrue(questions[1].text("id")!!.toLong() !in first.flatMap { g -> g.questions().map { it.text("id")!!.toLong() } })
        assertFalse(gone in edit().groups().flatMap { g -> g.questions().map { it.text("id") } })
        assertEquals(2L, transaction(db) { FaqItemTbl.selectAll().count() })
    }

    @Test
    fun `the same tree again is 200 with no row`() = panel.app {
        val saved = save(threeGroups).bodyAsText()
        // The form sends back what it was given, ids and sortOrders included.
        val again = save(saved)

        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
        assertEquals(1, auditRows())
    }

    @Test
    fun `two groups with one anchor, and a question id the shop doesn't have, are 400 on their paths`() = panel.app {
        val twins = save("""[{"id":"reserve","name":"یک","questions":[]},{"id":"reserve","name":"دو","questions":[]}]""")
        assertEquals(listOf("1.id FAQ_ANCHOR_DUPLICATE"), twins.fieldErrors())

        val stranger = save("""[{"id":"reserve","name":"رزرو","questions":[{"id":999999,"question":"س","answer":"<p>ج</p>"}]}]""")
        assertEquals(listOf("0.questions.0.id FAQ_ITEM_NOT_FOUND"), stranger.fieldErrors())
        assertEquals(0L, transaction(db) { FaqGroupTbl.selectAll().count() })
    }

    @Test
    fun `q finds only matching groups and questions, with Arabic letters too - the match marked in text, never in a tag or an address`() = panel.app {
        save(threeGroups)

        val found = faq("رزرو").json()
        assertEquals(ContractSchemas.propertiesOf("FaqResponse"), found.keys)
        assertEquals("2", found.text("totalMatches"))
        assertNull(found.text("emptyMessage"))
        val groups = found.getValue("groups").jsonArray.map { it.jsonObject }
        assertEquals(listOf("reserve"), groups.map { it.text("id") })
        val first = groups.single().questions().first()
        assertEquals("<mark>رزرو</mark> ۴ روزه چطور کار می‌کنه؟", first.text("questionHighlighted"))
        assertEquals(
            "<p>الان کامل پرداخت کن؛ <b>همه‌ی <mark>رزرو</mark>ها</b> یکجا می‌رسن. <a href=\"/faq#shipping\">ارسال</a></p>",
            first.text("answerHighlighted"),
        )

        // «کار» typed with an Arabic kāf, and «۴ روزه» with a Latin digit, still find the first question.
        val arabic = faq("كار").json()
        assertEquals("1", arabic.text("totalMatches"))
        assertEquals("رزرو ۴ روزه چطور <mark>کار</mark> می‌کنه؟", arabic.getValue("groups").jsonArray.single().jsonObject.questions().single().text("questionHighlighted"))
        assertEquals("1", faq("4 روزه").json().text("totalMatches"))
        // A tag's name and an address are not text: nothing found.
        assertEquals("0", faq("shipping").json().text("totalMatches"))
    }

    @Test
    fun `a question's own HTML is shown escaped in its highlight`() = panel.app {
        save(threeGroups)

        val question = faq("بازگشت").json().getValue("groups").jsonArray.single().jsonObject.questions().single()

        assertEquals("&lt;b&gt;<mark>بازگشت</mark>&lt;/b&gt; چطوریه؟", question.text("questionHighlighted"))
    }

    @Test
    fun `no match - no groups, no count and the empty message - without q everything, no message`() = panel.app {
        save(threeGroups)

        val none = faq("جواهرات").json()
        assertEquals(listOf("0", "[]"), listOf(none.text("totalMatches"), none.getValue("groups").toString()))
        assertEquals("سوالی با این کلمه پیدا نکردیم؛ از پشتیبانی بپرس", none.text("emptyMessage"))

        val all = faq().json()
        assertEquals(listOf("4", null), listOf(all.text("totalMatches"), all.text("emptyMessage")))
        assertEquals(3, all.getValue("groups").jsonArray.size)
    }

    @Test
    fun `q over 100 letters is 400 TEXT_TOO_LONG on q`() = panel.app {
        assertEquals(listOf("q TEXT_TOO_LONG"), faq("ر".repeat(101)).fieldErrors())
    }

    @Test
    fun `the site's FAQ is cached and answers 304 to its ETag - the panel's is never cached`() = panel.app {
        save(threeGroups)
        val first = faq()
        val etag = first.headers[HttpHeaders.ETag]!!

        assertEquals(HttpStatusCode.NotModified, faq(ifNoneMatch = etag).status)
        assertEquals(null, edit().headers[HttpHeaders.ETag])
        assertFalse(edit().headers[HttpHeaders.CacheControl].orEmpty().contains("public"))
    }

    @Test
    fun `OPERATOR and SUPPORT are 403 on reading and saving the panel's FAQ - ADMIN may`() = panel.app {
        listOf(Role.OPERATOR, Role.SUPPORT).forEach { role ->
            val caller = panel.callerWith(role)
            assertEquals(HttpStatusCode.Forbidden, edit(caller).status, "$role")
            assertEquals(HttpStatusCode.Forbidden, save(threeGroups, caller).status, "$role")
        }
        assertEquals(HttpStatusCode.OK, save(threeGroups, panel.callerWith(Role.ADMIN)).status)
    }

    @Test
    fun `two saves at the same moment, 10 times - both 200, one after the other, never two trees mixed`() {
        // Both are past the form and about to take the FAQ's lock.
        var bothIn = CountDownLatch(2)
        val faq = object : FaqRepository by FaqRepositoryImpl() {
            override fun lock() {
                bothIn.countDown()
                bothIn.await(5, TimeUnit.SECONDS)
                FaqRepositoryImpl().lock()
            }
        }
        val content = panel.adminContent
        val useCases = AdminContentUseCases(
            panel.checkPanelAccess, content.listPages, content.getPage, content.savePage, content.listFaq,
            SaveFaqUc(panel.transactions, faq, JsoupHtmlSanitizer, panel.auditTrail, clock), content.listAnnouncements, content.saveAnnouncements,
        )
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureHttp(trustedProxyHops = 0)
                configureAuthentication(panel.accessTokens, panel.accountCheck)
                routing { route(API_V1) { webV1AdminContent(useCases, PanelTestStack.SITE_URL) } }
            }
            repeat(10) { round ->
                bothIn = CountDownLatch(2)
                val trees = listOf("one", "two").map { anchor ->
                    """[{"id":"$anchor","name":"گروه","questions":[{"question":"س $round","answer":"<p>ج</p>"}]}]"""
                }

                val statuses = coroutineScope { trees.map { async { save(it).status } }.awaitAll() }

                assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK), statuses, "round $round")
                assertEquals(1L, transaction(db) { FaqGroupTbl.selectAll().count() }, "round $round: one tree, the last")
            }
        }
    }
}
