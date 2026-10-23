package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.usecase.contact.usecase.command.CreateContactMessageUc
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.contact.TicketCodeGenerator
import com.kiva.domain.aggregate.contact.model.ContactMessageDraft
import com.kiva.domain.aggregate.contact.model.valueobject.ContactStatus
import com.kiva.domain.aggregate.contact.model.valueobject.ContactTopic
import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.ContactMessageRepositoryImpl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.random.RandomGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The «تماس با ما» form (roadmap step 13.2) over the real stack on kiva_test: `GET /contact/topics` and
 * `POST /contact/messages`, a guest's and a customer's, its two limits, and what reaches the log.
 */
class WebV1ContactOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-23T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val repository = ContactMessageRepositoryImpl()

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun body(
        phone: String = "09123334455",
        email: String? = null,
        orderCode: String? = null,
        fullName: String = "سارا محمدی",
        message: String = "سلام، سفارشم هنوز عکسش برام ارسال نشده.",
        topic: String = "ORDER_FOLLOWUP",
    ): String = buildJsonObject {
        put("topic", topic)
        put("fullName", fullName)
        put("phone", phone)
        put("email", email)
        put("orderCode", orderCode)
        put("message", message)
    }.toString()

    private suspend fun ApplicationTestBuilder.send(body: String, bearer: String? = null, forwardedFor: String? = null): HttpResponse =
        client.post("$API_V1/contact/messages") {
            contentType(ContentType.Application.Json)
            bearer?.let { header(HttpHeaders.Authorization, it) }
            forwardedFor?.let { header(HttpHeaders.XForwardedFor, it) }
            setBody(body)
        }

    private fun stored(code: String) = transaction(panel.db) { repository.find(TicketCode.constructFromPersisted(code)) }

    @Test
    fun `the five topics with their labels, the order field shown for two, cached with 304`() = panel.app {
        val response = client.get("$API_V1/contact/topics")

        assertEquals(HttpStatusCode.OK, response.status)
        val topics = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(ContactTopic.entries.map { it.name }, topics.map { it["value"]!!.jsonPrimitive.content })
        assertEquals(listOf("پیگیری سفارش", "سؤال درباره‌ی محصول", "مرجوعی", "همکاری", "سایر"), topics.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf(true, false, true, false, false), topics.map { it["requiresOrderCode"]!!.jsonPrimitive.content.toBoolean() })
        assertEquals(ContractSchemas.propertiesOf("ContactTopicOption"), topics.first().keys)
        val again = client.get("$API_V1/contact/topics") { header(HttpHeaders.IfNoneMatch, response.headers[HttpHeaders.ETag]!!) }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    @Test
    fun `a guest's message - 201 with its CT- code, stored OPEN, normalized, without an account`() = panel.app {
        val response = send(body(phone = "+98 912 333 4455", email = " Sara@Example.com ", orderCode = "kv-۲۱۸۳۴۰", message = "سلام\nسفارشم کجاست؟ ممنون"))

        assertEquals(HttpStatusCode.Created, response.status)
        val json = response.json()
        assertEquals(ContractSchemas.propertiesOf("ContactMessageResponse"), json.keys)
        val code = json["ticketCode"]!!.jsonPrimitive.content
        assertTrue(Regex("CT-[0-9]{6}").matches(code), code)
        assertEquals(clock.now.toString(), json["createdAt"]!!.jsonPrimitive.content)
        assertEquals("پیامت رسید! کد پیگیریت ${code}ـه؛ به زودی باهات تماس می‌گیریم.", json["message"]!!.jsonPrimitive.content)
        val message = assertNotNull(stored(code))
        assertEquals(listOf("09123334455", "sara@example.com", "KV-218340"), listOf(message.phone.value, message.email?.value, message.orderCode?.value))
        assertEquals("سلام\nسفارشم کجاست؟ ممنون", message.message)
        assertEquals(ContactStatus.OPEN, message.status)
        assertNull(message.userId)
        assertNull(message.reply)
    }

    @Test
    fun `a logged-in customer's message is theirs, and a broken token is 401, never a guest's`() = panel.app {
        val sara = panel.callerWith(Role.CUSTOMER)

        val response = send(body(), bearer = sara.bearer)

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(sara.userId, stored(response.json()["ticketCode"]!!.jsonPrimitive.content)?.userId?.value)
        assertEquals(HttpStatusCode.Unauthorized, send(body(), bearer = "Bearer not-a-token").status)
    }

    @Test
    fun `every problem at once - 400 with each under its field, and nothing stored`() = panel.app {
        val response = send(body(phone = "0912", email = "sara@", fullName = "سا", message = "سلام خوبی", topic = "COMPLAINT", orderCode = "218340"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val errors = response.json()["errors"]!!.jsonArray.map { it.jsonObject["field"]!!.jsonPrimitive.content to it.jsonObject["code"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("topic" to "CONTACT_TOPIC_INVALID", "fullName" to "TEXT_TOO_SHORT", "phone" to "PHONE_INVALID", "email" to "EMAIL_INVALID", "orderCode" to "ORDER_CODE_INVALID", "message" to "TEXT_TOO_SHORT"),
            errors,
        )
        assertEquals(0, transaction(panel.db) { repository.createdSince(IranianMobileNumber.constructFromPersisted("09123334455"), Instant.EPOCH) }.size)
    }

    @Test
    fun `the sixth message from one address in an hour is 429 with Retry-After - another address is let through`() = panel.app(trustedProxyHops = 1) {
        repeat(5) { assertEquals(HttpStatusCode.Created, send(body(phone = "0912000000$it"), forwardedFor = "5.6.7.8").status, "message ${it + 1}") }

        val sixth = send(body(phone = "09120000009"), forwardedFor = "5.6.7.8")

        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        val wait = assertNotNull(sixth.headers[HttpHeaders.RetryAfter]).toLong()
        assertTrue(wait in 1..3600, "$wait")
        assertEquals(wait, sixth.json()["meta"]!!.jsonObject["retryAfterSeconds"]!!.jsonPrimitive.content.toLong())
        assertEquals(HttpStatusCode.Created, send(body(phone = "09120000009"), forwardedFor = "9.9.9.9").status)
    }

    @Test
    fun `the sixth message of one mobile in a day, from other addresses, is 429 until the oldest is a day old`() = panel.app(trustedProxyHops = 1) {
        repeat(5) {
            assertEquals(HttpStatusCode.Created, send(body(phone = "۰۹۱۲۳۳۳۴۴۵۵"), forwardedFor = "10.0.0.$it").status, "message ${it + 1}")
            clock.advance(Duration.ofHours(2))
        }
        // The first was sent 10 hours ago: it leaves the day in 14.

        val sixth = send(body(phone = "09123334455"), forwardedFor = "10.0.1.1")

        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        val json = sixth.json()
        assertEquals("RATE_LIMITED", json["code"]!!.jsonPrimitive.content)
        assertEquals(14L * 3600, json["meta"]!!.jsonObject["retryAfterSeconds"]!!.jsonPrimitive.content.toLong())
        assertEquals((14L * 3600).toString(), sixth.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.Created, send(body(phone = "09124445566"), forwardedFor = "10.0.1.1").status, "another mobile")
        clock.advance(Duration.ofHours(14))
        assertEquals(HttpStatusCode.Created, send(body(phone = "09123334455"), forwardedFor = "10.0.1.2").status, "a day after the first")
    }

    @Test
    fun `eight messages of one mobile at once - five go in, three wait for the lock and are 429`() = panel.app(trustedProxyHops = 1) {
        val statuses = coroutineScope {
            (1..8).map { async { send(body(phone = "09125556677"), forwardedFor = "10.0.2.$it").status } }.awaitAll()
        }

        assertEquals(mapOf(HttpStatusCode.Created to 5, HttpStatusCode.TooManyRequests to 3), statuses.groupingBy { it }.eachCount())
        assertEquals(5, transaction(panel.db) { repository.createdSince(IranianMobileNumber.constructFromPersisted("09125556677"), Instant.EPOCH) }.size)
    }

    @Test
    fun `a code already taken is drawn again - and when none is free in five tries, an error, nothing stored`() {
        val taken = TicketCode.ofNumber(111_111)
        val input = ContactMessageDraft.Input("OTHER", "سارا محمدی", "09123334455", null, null, "سلام، یه سؤال داشتم درباره‌ی کیف.")
        fun useCase(vararg numbers: Int) = CreateContactMessageUc(panel.transactions, repository, TicketCodeGenerator(fixed(*numbers)), clock)

        val first = runBlocking { useCase(111_111).execute(CreateContactMessageUc.Cmd(null, input)) }
        val second = runBlocking { useCase(111_111, 111_111, 222_222).execute(CreateContactMessageUc.Cmd(null, input)) }

        assertEquals(taken, (first as Result.Success).value.ticketCode)
        assertEquals(TicketCode.ofNumber(222_222), (second as Result.Success).value.ticketCode)
        assertFailsWith<IllegalStateException> { runBlocking { useCase(111_111, 111_111, 111_111, 111_111, 111_111, 222_222).execute(CreateContactMessageUc.Cmd(null, input)) } }
        assertEquals(2, transaction(panel.db) { repository.createdSince(IranianMobileNumber.constructFromPersisted("09123334455"), Instant.EPOCH) }.size)
    }

    @Test
    fun `the log has the code and the topic, never the name, the mobile, the email or the text`() {
        val log = logOf {
            panel.app {
                assertEquals(HttpStatusCode.Created, send(body(phone = "09127778899", email = "zohreh.k@example.com", fullName = "زهره کاظمی‌نژاد", message = "متن محرمانه‌ی پیام برای پشتیبانی")).status)
                assertEquals(HttpStatusCode.BadRequest, send(body(phone = "09127778899", email = "zohreh.k@example.com", fullName = "زهره کاظمی‌نژاد", message = "کوتاه")).status)
            }
        }

        val lines = log.joinToString("\n") { it.formattedMessage }
        assertTrue("CT-" in lines && "ORDER_FOLLOWUP" in lines, "the code and the topic are in the log")
        listOf("09127778899", "zohreh.k@example.com", "زهره", "کاظمی", "محرمانه", "کوتاه").forEach { assertTrue(it !in lines, "$it is in the log") }
        assertTrue(log.any { it.loggerName == "Exposed" }, "the SQL log was caught")
    }

    /** Every line [block] logs, at the levels the app is set to, and with the SQL log on whatever logback.xml says. */
    private fun logOf(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val sql = LoggerFactory.getLogger("Exposed") as Logger
        val sqlLevel = sql.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        sql.level = Level.DEBUG
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            sql.level = sqlLevel
        }
        return appender.list.toList()
    }

    private fun fixed(vararg numbers: Int): RandomGenerator = object : RandomGenerator {
        private val next = numbers.iterator()

        override fun nextLong(): Long = error("not used")

        override fun nextInt(bound: Int): Int = next.next()
    }
}
