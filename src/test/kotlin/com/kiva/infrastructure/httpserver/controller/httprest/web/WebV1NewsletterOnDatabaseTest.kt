package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.usecase.contact.usecase.command.DeleteOldContactDataUc
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.tbl.NewsletterSubscriptionTbl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The newsletter (roadmap step 13.4) over the real stack on kiva_test: subscribing (always the same 200), the welcome
 * mail and its unsubscribe link, leaving, the panel's list, the limit per address, and the nightly clean-up of old
 * contact data.
 */
class WebV1NewsletterOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-23T08:00:00Z"))
    private val panel = PanelTestStack(clock)

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.subscribe(email: String, source: String? = "footer", forwardedFor: String? = null): HttpResponse =
        client.post("$API_V1/newsletter/subscriptions") {
            contentType(ContentType.Application.Json)
            forwardedFor?.let { header(HttpHeaders.XForwardedFor, it) }
            setBody(if (source == null) """{"email":"$email"}""" else """{"email":"$email","source":"$source"}""")
        }

    private suspend fun ApplicationTestBuilder.unsubscribe(token: String): HttpResponse =
        client.post("$API_V1/newsletter/subscriptions/unsubscribe") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$token"}""")
        }

    private suspend fun ApplicationTestBuilder.list(query: String = "", caller: PanelTestStack.Caller = panel.callerWith(Role.ADMIN)): HttpResponse =
        client.get("$API_V1/admin/newsletter/subscriptions$query") { header(HttpHeaders.Authorization, caller.bearer) }

    private fun row(email: String) = transaction(panel.db) {
        NewsletterSubscriptionTbl.selectAll().where { NewsletterSubscriptionTbl.email eq email }.single().let {
            Triple(it[NewsletterSubscriptionTbl.status].name, it[NewsletterSubscriptionTbl.sourceName], it[NewsletterSubscriptionTbl.token])
        }
    }

    /** The token of the last welcome mail's link. */
    private fun lastLinkToken(): String {
        val body = panel.email.sent.last().textBody
        val link = Regex("https://kivashop\\.test/newsletter/unsubscribe\\?token=(nl_[0-9A-Za-z]{32})").find(body)
        return link?.groupValues?.get(1) ?: error("no unsubscribe link in: $body")
    }

    @Test
    fun `a new email - 200 and a welcome mail with its unsubscribe link - again, in other capitals, the same 200 and no mail`() = panel.app {
        val first = subscribe("Sara@Example.com")
        val again = subscribe("sara@example.com", source = "Home Page")

        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK), listOf(first.status, again.status))
        assertEquals(ContractSchemas.propertiesOf("MessageResponse"), first.json().keys)
        assertEquals("عضو خبرنامه کیوا شدی!", first.json().text("message"))
        assertEquals(first.bodyAsText(), again.bodyAsText())
        val mail = panel.email.sent.single()
        assertEquals("sara@example.com", mail.to.value)
        assertEquals(Triple("ACTIVE", "footer", lastLinkToken()), row("sara@example.com"))
    }

    @Test
    fun `leaving with the link - again 204 - a made-up token of the shape 204 - not of the shape 400 - back again is ACTIVE with a welcome`() = panel.app {
        assertEquals(HttpStatusCode.OK, subscribe("sara@example.com", source = null).status)
        val token = lastLinkToken()

        assertEquals(HttpStatusCode.NoContent, unsubscribe(token).status)
        assertEquals("UNSUBSCRIBED", row("sara@example.com").first)
        clock.advance(Duration.ofDays(1))
        assertEquals(HttpStatusCode.NoContent, unsubscribe(token).status)
        assertEquals(HttpStatusCode.NoContent, unsubscribe("nl_" + "Z".repeat(32)).status)
        val bad = unsubscribe("nl_short")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("token" to "UNSUBSCRIBE_TOKEN_INVALID", bad.json()["errors"]!!.jsonArray.single().jsonObject.let { it.text("field") to it.text("code") })

        assertEquals(HttpStatusCode.OK, subscribe("sara@example.com").status)
        assertEquals(Triple("ACTIVE", null, token), row("sara@example.com"))
        assertEquals(2, panel.email.sent.size)
        assertEquals(token, lastLinkToken())
    }

    @Test
    fun `not an email, or none, is 400 on email - and when the mail fails the subscription stands`() = panel.app {
        val bad = subscribe("sara@")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("email" to "EMAIL_INVALID", bad.json()["errors"]!!.jsonArray.single().jsonObject.let { it.text("field") to it.text("code") })
        val none = client.post("$API_V1/newsletter/subscriptions") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals("email" to "FIELD_REQUIRED", none.json()["errors"]!!.jsonArray.single().jsonObject.let { it.text("field") to it.text("code") })

        panel.email.down = true
        try {
            assertEquals(HttpStatusCode.OK, subscribe("nima@example.com").status)
        } finally {
            panel.email.down = false
        }
        assertEquals("ACTIVE", row("nima@example.com").first)
    }

    @Test
    fun `the panel's list - ADMIN only, newest first, by status and a part of the email`() = panel.app {
        assertEquals(HttpStatusCode.OK, subscribe("sara@example.com").status)
        clock.advance(Duration.ofMinutes(5))
        assertEquals(HttpStatusCode.OK, subscribe("nima@kivashop.ir", source = null).status)
        clock.advance(Duration.ofMinutes(5))
        assertEquals(HttpStatusCode.OK, subscribe("reza@example.org").status)
        assertEquals(HttpStatusCode.NoContent, unsubscribe(lastLinkToken()).status)

        val all = list().json()
        val items = all["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("reza@example.org", "nima@kivashop.ir", "sara@example.com"), items.map { it.text("email") })
        assertEquals(ContractSchemas.propertiesOf("NewsletterSubscription"), items.first().keys)
        assertEquals(listOf("UNSUBSCRIBED", null), listOf(items.first().text("status"), items[1].text("source")))
        assertEquals(listOf("reza@example.org", "sara@example.com"), list("?q=EXAM").json()["items"]!!.jsonArray.map { it.jsonObject.text("email") })
        assertEquals(listOf("sara@example.com"), list("?q=exam&status=ACTIVE").json()["items"]!!.jsonArray.map { it.jsonObject.text("email") })
        assertEquals("2", list("?status=ACTIVE").json()["meta"]!!.jsonObject.text("totalItems"))
        assertEquals(emptyList(), list("?q=100%25").json()["items"]!!.jsonArray.toList())
        assertEquals(HttpStatusCode.BadRequest, list("?status=GONE").status)
        listOf(Role.SUPPORT, Role.OPERATOR, Role.CONTENT_EDITOR).forEach { assertEquals(HttpStatusCode.Forbidden, list(caller = panel.callerWith(it)).status, "$it") }
    }

    @Test
    fun `the sixth subscribe from one address in an hour is 429 - another address is let through`() = panel.app(trustedProxyHops = 1) {
        repeat(5) { assertEquals(HttpStatusCode.OK, subscribe("s$it@example.com", forwardedFor = "5.6.7.8").status, "request ${it + 1}") }

        val sixth = subscribe("s9@example.com", forwardedFor = "5.6.7.8")

        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertTrue(sixth.headers[HttpHeaders.RetryAfter]!!.toLong() > 0)
        assertEquals(HttpStatusCode.OK, subscribe("s9@example.com", forwardedFor = "9.9.9.9").status)
    }

    @Test
    fun `the log never has the email or the token`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        var token = ""
        try {
            panel.app {
                assertEquals(HttpStatusCode.OK, subscribe("zohreh.k@example.com").status)
                token = lastLinkToken()
                assertEquals(HttpStatusCode.NoContent, unsubscribe(token).status)
            }
        } finally {
            root.detachAppender(appender)
        }
        val lines = appender.list.joinToString("\n") { it.formattedMessage }
        assertTrue("SubscribeNewsletterUc" in lines, "the use case's lines were caught")
        listOf("zohreh.k@example.com", token).forEach { assertTrue(it !in lines, "$it is in the log") }
    }

    @Test
    fun `the nightly clean-up - CLOSED messages a year old and emails unsubscribed 90 days ago go, the rest stays`() = panel.app {
        listOf("old@example.com", "recent@example.com", "active@example.com").forEach { assertEquals(HttpStatusCode.OK, subscribe(it).status) }
        val codes = (1..3).map {
            client.post("$API_V1/contact/messages") {
                contentType(ContentType.Application.Json)
                setBody("""{"topic":"OTHER","fullName":"سارا محمدی","phone":"0912333445$it","message":"سلام، یه سؤال داشتم."}""")
            }.json().text("ticketCode")!!
        }
        val support = panel.callerWith(Role.SUPPORT)
        codes.take(2).forEach { code ->
            client.patch("$API_V1/admin/contact-messages/$code") {
                header(HttpHeaders.Authorization, support.bearer)
                contentType(ContentType.Application.Json)
                setBody("""{"status":"CLOSED"}""")
            }
        }
        transaction(panel.db) {
            fun daysAgo(days: Long) = clock.now.minus(Duration.ofDays(days)).atOffset(ZoneOffset.UTC)
            exec("""UPDATE contactmessage_tbl SET "updatedAt" = '${daysAgo(366)}', "createdAt" = '${daysAgo(400)}' WHERE "ticketCode" IN ('${codes[0]}', '${codes[2]}')""")
            exec("""UPDATE contactmessage_tbl SET "updatedAt" = '${daysAgo(364)}' WHERE "ticketCode" = '${codes[1]}'""")
            mapOf("old@example.com" to 91L, "recent@example.com" to 89L).forEach { (email, days) ->
                NewsletterSubscriptionTbl.update({ NewsletterSubscriptionTbl.email eq email }) {
                    it[status] = com.kiva.domain.aggregate.newsletter.model.valueobject.SubscriptionStatus.UNSUBSCRIBED
                    it[unsubscribedAt] = daysAgo(days)
                }
            }
        }

        val deleted = runBlocking { DeleteOldContactDataUc(panel.transactions, panel.contactMessages, panel.subscriptions, clock).execute(DeleteOldContactDataUc.Cmd) }

        assertEquals(DeleteOldContactDataUc.Deleted(messages = 1, subscriptions = 1), deleted.getOrNull())
        val left = transaction(panel.db) { exec("SELECT \"ticketCode\" FROM contactmessage_tbl ORDER BY \"ticketCode\"") { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() } }
        assertEquals(codes.drop(1).sorted(), left)
        assertEquals(listOf("active@example.com", "recent@example.com"), transaction(panel.db) { NewsletterSubscriptionTbl.selectAll().map { it[NewsletterSubscriptionTbl.email] }.sorted() })
    }
}
