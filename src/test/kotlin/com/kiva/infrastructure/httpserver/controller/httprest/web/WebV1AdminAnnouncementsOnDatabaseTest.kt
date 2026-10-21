package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.content.usecase.command.SaveAnnouncementsUc
import com.kiva.domain.aggregate.announcement.repository.AnnouncementBarRepository
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.AnnouncementBarRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AnnouncementTbl
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
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

/** The announcement bar in the panel (`/admin/announcements`; step 5.3) on kiva_test. */
class WebV1AdminAnnouncementsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-21T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val db = TestDatabase.database

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        // No other test writes the bar.
        transaction(db) { AnnouncementTbl.deleteAll() }
    }

    private val editor by lazy { panel.callerWith(Role.CONTENT_EDITOR) }

    private suspend fun ApplicationTestBuilder.save(body: String, caller: PanelTestStack.Caller = editor): HttpResponse =
        client.put("$API_V1/admin/announcements") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.list(caller: PanelTestStack.Caller = editor): HttpResponse =
        client.get("$API_V1/admin/announcements") { header(HttpHeaders.Authorization, caller.bearer) }

    private suspend fun HttpResponse.items(): List<JsonObject> = Json.parseToJsonElement(bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        Json.parseToJsonElement(bodyAsText()).jsonObject["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun auditRows(): Int = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.actorId eq editor.userId) and (AuditLogTbl.action eq "ANNOUNCEMENTS_SAVED") }.count().toInt()
    }

    private val three = """[
        {"icon":"camera","text":"هرچی ببینی، همون می‌رسه","url":null},
        {"icon":"truck","text":"ارسال رایگان با پست","url":"/faq#shipping","startsAt":"2026-10-25T00:00:00+03:30"},
        {"icon":"timer","text":"جشنواره‌ی پاییزه تموم شد","url":"https://kiva.ir/campaigns/paeez","endsAt":"2026-10-01T00:00:00Z"}
    ]"""

    @Test
    fun `saved in order, scheduled and ended ones too, each with a new id - the panel's list is the same`() = panel.app {
        val saved = save(three)

        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val items = saved.items()
        assertEquals(ContractSchemas.propertiesOf("Announcement"), items.first().keys)
        assertEquals(listOf("0", "1", "2"), items.map { it.text("sortOrder") })
        assertEquals(listOf(null, "/faq#shipping", "https://kiva.ir/campaigns/paeez"), items.map { it.text("url") })
        assertEquals(listOf(null, "2026-10-24T20:30:00Z", null), items.map { it.text("startsAt") })
        assertEquals(3, items.map { it.text("id") }.toSet().size)
        assertEquals(items, list().items())
        assertEquals(1, auditRows())
    }

    @Test
    fun `edited by id, removed by leaving it out, reordered by the array`() = panel.app {
        val (first, second, third) = save(three).items().map { it.text("id")!! }

        val edited = save("""[{"id":$third,"text":"جشنواره‌ی زمستونه"},{"id":$first,"text":"هرچی ببینی، همون می‌رسه","icon":"camera"}]""").items()

        assertEquals(listOf(third, first), edited.map { it.text("id") })
        assertEquals(listOf("جشنواره‌ی زمستونه", null), listOf(edited[0].text("text"), edited[0].text("endsAt")))
        assertEquals(2L, transaction(db) { AnnouncementTbl.selectAll().count() })
        assertEquals(false, edited.any { it.text("id") == second })
    }

    @Test
    fun `the same list again is 200 with no row`() = panel.app {
        val saved = save(three).bodyAsText()

        assertEquals(HttpStatusCode.OK, save(saved).status)
        assertEquals(1, auditRows())
    }

    @Test
    fun `11 announcements, a javascript link, an id the shop doesn't have and a bad time are 400 on their paths`() = panel.app {
        assertEquals(listOf("announcements TOO_MANY_ITEMS"), save("[" + List(11) { """{"text":"پیام $it"}""" }.joinToString() + "]").fieldErrors())
        assertEquals(listOf("0.url URL_INVALID"), save("""[{"text":"بد","url":"javascript:alert(1)"}]""").fieldErrors())
        assertEquals(listOf("0.id ANNOUNCEMENT_NOT_FOUND"), save("""[{"id":999999,"text":"غریبه"}]""").fieldErrors())
        assertEquals(listOf("0.startsAt DATE_TIME_INVALID"), save("""[{"text":"زمان","startsAt":"فردا"}]""").fieldErrors())
        assertEquals(0L, transaction(db) { AnnouncementTbl.selectAll().count() })
    }

    @Test
    fun `OPERATOR and SUPPORT are 403 - ADMIN may`() = panel.app {
        listOf(Role.OPERATOR, Role.SUPPORT).forEach { role ->
            val caller = panel.callerWith(role)
            assertEquals(HttpStatusCode.Forbidden, list(caller).status, "$role")
            assertEquals(HttpStatusCode.Forbidden, save(three, caller).status, "$role")
        }
        assertEquals(HttpStatusCode.OK, save(three, panel.callerWith(Role.ADMIN)).status)
    }

    @Test
    fun `two saves at the same moment, 10 times - both 200, one after the other, never two lists mixed`() {
        var bothIn = CountDownLatch(2)
        val bar = object : AnnouncementBarRepository by AnnouncementBarRepositoryImpl() {
            override fun lock() {
                bothIn.countDown()
                bothIn.await(5, TimeUnit.SECONDS)
                AnnouncementBarRepositoryImpl().lock()
            }
        }
        val content = panel.adminContent
        val useCases = AdminContentUseCases(
            panel.checkPanelAccess, content.listPages, content.getPage, content.savePage, content.listFaq, content.saveFaq, content.listAnnouncements,
            SaveAnnouncementsUc(panel.transactions, bar, panel.auditTrail, clock),
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

                val statuses = coroutineScope { listOf("یک", "دو").map { text -> async { save("""[{"text":"$text $round"}]""").status } }.awaitAll() }

                assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK), statuses, "round $round")
                assertEquals(1L, transaction(db) { AnnouncementTbl.selectAll().count() }, "round $round: one list, the last")
            }
        }
    }
}
