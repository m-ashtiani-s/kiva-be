package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The panel's contact messages (roadmap step 13.3) over the real stack on kiva_test: the list and its filters, and a
 * change - status, internal note, a reply by SMS (only the code goes) or by email (the whole text) - with its change log.
 */
class WebV1AdminContactMessagesOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-23T08:00:00Z"))
    private val panel = PanelTestStack(clock)

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
    }

    private val support by lazy { panel.caller(PanelTestStack.phone(31), Role.SUPPORT, name = "نگار پشتیبان") }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.message(
        topic: String = "ORDER_FOLLOWUP",
        email: String? = null,
        phone: String = "09123334455",
        text: String = "سلام، سفارشم هنوز عکسش برام ارسال نشده.",
    ): String {
        val body = buildJsonObject {
            put("topic", topic)
            put("fullName", "سارا محمدی")
            put("phone", phone)
            put("email", email)
            put("message", text)
        }
        val response = client.post("$API_V1/contact/messages") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        clock.advance(Duration.ofMinutes(1))
        return response.json().text("ticketCode")!!
    }

    private suspend fun ApplicationTestBuilder.change(code: String, body: String, caller: PanelTestStack.Caller = support): HttpResponse =
        client.patch("$API_V1/admin/contact-messages/$code") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.list(query: String = "", caller: PanelTestStack.Caller = support): HttpResponse =
        client.get("$API_V1/admin/contact-messages$query") { header(HttpHeaders.Authorization, caller.bearer) }

    private fun errorsOf(json: JsonObject) = json["errors"]!!.jsonArray.map { it.jsonObject.text("field") to it.jsonObject.text("code") }

    /** The change log rows of [code] written by [support], oldest first: (action, changes as JSON). */
    private fun auditOf(code: String) = transaction(panel.db) {
        AuditLogTbl.selectAll()
            .where { (AuditLogTbl.entityType eq "CONTACT_MESSAGE") and (AuditLogTbl.entityId eq code) and (AuditLogTbl.actorId eq support.userId) }
            .orderBy(AuditLogTbl.id)
            .map { it[AuditLogTbl.action] to it[AuditLogTbl.changes]?.toString() }
    }

    @Test
    fun `the list - newest first, every field, filtered by status and topic, an unknown value 400`() = panel.app {
        val first = message(topic = "RETURN")
        val second = message(topic = "OTHER", email = "sara@example.com")
        assertEquals(HttpStatusCode.OK, change(second, """{"status":"IN_PROGRESS"}""").status)

        val all = list().json()
        val items = all["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(second, first), items.map { it.text("ticketCode") })
        assertEquals(ContractSchemas.propertiesOf("AdminContactMessage"), items.first().keys)
        assertEquals(listOf("OTHER", "سارا محمدی", "09123334455", "sara@example.com", "IN_PROGRESS"), listOf("topic", "fullName", "phone", "email", "status").map { items.first().text(it) })
        assertEquals(2, all["meta"]!!.jsonObject.text("totalItems")!!.toInt())

        assertEquals(listOf(first), list("?status=OPEN").json()["items"]!!.jsonArray.map { it.jsonObject.text("ticketCode") })
        assertEquals(listOf(second), list("?topic=OTHER").json()["items"]!!.jsonArray.map { it.jsonObject.text("ticketCode") })
        assertEquals(emptyList(), list("?topic=RETURN&status=CLOSED").json()["items"]!!.jsonArray.toList())
        val bad = list("?status=DONE&topic=COMPLAINT")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals(listOf("status" to "CONTACT_STATUS_INVALID", "topic" to "CONTACT_TOPIC_INVALID"), errorsOf(bad.json()))
    }

    @Test
    fun `{} is 200 unchanged and leaves no change log row - the same status neither`() = panel.app {
        val code = message()

        val nothing = change(code, "{}")
        val same = change(code, """{"status":"OPEN"}""")

        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK), listOf(nothing.status, same.status))
        assertEquals("OPEN", nothing.json().text("status"))
        assertEquals(emptyList(), auditOf(code))
    }

    @Test
    fun `a reply by SMS - the SMS has the code and never the reply's text, the reply is kept with when and by whom`() = panel.app {
        val code = message()

        val response = change(code, """{"reply":"سلام سارا، عکس‌ها امروز می‌رن.","replyChannel":"SMS","status":"IN_PROGRESS"}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val json = response.json()
        assertEquals(listOf("سلام سارا، عکس‌ها امروز می‌رن.", "SMS", clock.now.toString(), "نگار پشتیبان", "IN_PROGRESS"), listOf("reply", "replyChannel", "repliedAt", "repliedBy", "status").map { json.text(it) })
        assertEquals(listOf("09123334455" to code), panel.sms.contactReplied.map { it.first.value to it.second.value })
        assertEquals(emptyList(), panel.email.sent)
        val rows = auditOf(code)
        assertEquals(listOf("CONTACT_MESSAGE_UPDATED"), rows.map { it.first })
        assertTrue(rows.single().second!!.contains("\"replied\"") && rows.single().second!!.contains("SMS") && rows.single().second!!.contains("IN_PROGRESS"), rows.toString())
        assertFalse(rows.single().second!!.contains("عکس‌ها"), "the change log holds the reply: $rows")
    }

    @Test
    fun `a reply by email carries its whole text - to a message without an email it is 400 EMAIL_NOT_AVAILABLE and nothing goes`() = panel.app {
        val withEmail = message(email = "Sara@Example.com")
        val without = message()

        val sent = change(withEmail, """{"reply":"سلام سارا،\nعکس‌ها امروز می‌رن.","replyChannel":"EMAIL"}""")
        val refused = change(without, """{"reply":"سلام","replyChannel":"EMAIL"}""")

        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
        val mail = panel.email.sent.single()
        assertEquals("sara@example.com", mail.to.value)
        assertTrue(withEmail in mail.subject, mail.subject)
        assertTrue("سلام سارا،\nعکس‌ها امروز می‌رن." in mail.textBody && "سارا محمدی" in mail.textBody && withEmail in mail.textBody, mail.textBody)
        assertEquals(emptyList(), panel.sms.contactReplied)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(listOf("replyChannel" to "EMAIL_NOT_AVAILABLE"), errorsOf(refused.json()))
        assertEquals(JsonNull, list("?status=OPEN").json()["items"]!!.jsonArray.map { it.jsonObject }.single { it.text("ticketCode") == without }["reply"])
    }

    @Test
    fun `a reply without its channel, a channel without a reply, an unknown status or channel, too long - 400 all at once`() = panel.app {
        val code = message()

        val noChannel = change(code, """{"reply":"سلام سارا"}""")
        val noReply = change(code, """{"replyChannel":"SMS","status":"DONE"}""")
        val bad = change(code, """{"reply":"س","replyChannel":"PIGEON","internalNote":"${"ن".repeat(1001)}"}""")

        assertEquals(listOf("replyChannel" to "REPLY_CHANNEL_REQUIRED"), errorsOf(noChannel.json()))
        assertEquals(listOf("status" to "CONTACT_STATUS_INVALID", "reply" to "REPLY_REQUIRED"), errorsOf(noReply.json()))
        assertEquals(listOf("reply" to "TEXT_TOO_SHORT", "replyChannel" to "REPLY_CHANNEL_INVALID", "internalNote" to "TEXT_TOO_LONG"), errorsOf(bad.json()))
        assertEquals(emptyList(), panel.sms.contactReplied)
        assertEquals(emptyList(), auditOf(code))
    }

    @Test
    fun `a second reply replaces the first and goes again - the note is replaced, cleared with null, and never in the change log`() = panel.app {
        val code = message()
        assertEquals(HttpStatusCode.OK, change(code, """{"reply":"اول","replyChannel":"SMS","internalNote":"مشتری عصبانیه"}""").status)
        clock.advance(Duration.ofMinutes(10))

        val againResponse = change(code, """{"reply":"دوم","replyChannel":"SMS","internalNote":"زنگ زدم"}""")
        assertEquals(HttpStatusCode.OK, againResponse.status, againResponse.bodyAsText())
        val again = againResponse.json()
        val cleared = change(code, """{"internalNote":null,"status":"CLOSED"}""").json()

        assertEquals(listOf("دوم", clock.now.toString(), "زنگ زدم"), listOf(again.text("reply"), again.text("repliedAt"), again.text("internalNote")))
        assertEquals(listOf("دوم", null, "CLOSED"), listOf(cleared.text("reply"), cleared.text("internalNote"), cleared.text("status")))
        assertEquals(2, panel.sms.contactReplied.size)
        val rows = auditOf(code)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.second!!.contains("internalNote") }, rows.toString())
        listOf("اول", "دوم", "عصبانی", "زنگ").forEach { word -> assertFalse(rows.any { it.second!!.contains(word) }, "the change log holds «$word»: $rows") }
    }

    @Test
    fun `when the SMS fails the reply is kept all the same`() = panel.app {
        val code = message()
        panel.sms.down = true
        try {
            val response = change(code, """{"reply":"سلام","replyChannel":"SMS"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("سلام", response.json().text("reply"))
        } finally {
            panel.sms.down = false
        }
    }

    @Test
    fun `a code nobody has, or that can't be one, is 404 - OPERATOR and CONTENT_EDITOR are 403`() = panel.app {
        val code = message()

        assertEquals(HttpStatusCode.NotFound, change(TicketCode.ofNumber(if (code == "CT-000001") 2 else 1).value, "{}").status)
        assertEquals(HttpStatusCode.NotFound, change("KV-218340", "{}").status)
        listOf(Role.OPERATOR, Role.CONTENT_EDITOR).map { panel.callerWith(it) }.forEach { other ->
            assertEquals(HttpStatusCode.Forbidden, list(caller = other).status)
            assertEquals(HttpStatusCode.Forbidden, change(code, "{}", caller = other).status)
        }
        assertEquals(HttpStatusCode.OK, list(caller = panel.callerWith(Role.ADMIN)).status)
    }
}
