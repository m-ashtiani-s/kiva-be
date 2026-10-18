package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 2.11 (roadmap section 3, row 15): the panel's work on orders with every log line caught, at the app's levels and
 * with the SQL log on: an order paid, its photos sent with a message, «همونه!», an internal note, shipped, followed by a
 * guest and on the daily list, delivered; another cancelled with a note and given back with the bank's reference.
 *
 * No line may carry a whole mobile number (the buyer's, the recipient's, the messenger's), the recipient's name, the
 * bank's reference, the text of a note or of the photos' message, or the IP the requests came from.
 */
class PanelOrdersLogScanOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val shippedOrders = ShippedOrders(panel)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `an order paid, photographed, noted, shipped, followed and delivered, and one cancelled and given back, leave nothing personal in the log`() {
        val sara = panel.caller(PanelTestStack.phone(951))
        val admin = panel.caller(PanelTestStack.phone(952), Role.ADMIN)
        val nila = listOf("black", "olive").map { panel.purchase.variantOf("nila-crossbody-bag", it) }
        val recipient = PaidOrders.Destination.TEHRAN

        val log = logOf {
            // As behind our nginx, so the caller's address is the one X-Forwarded-For names.
            panel.app(trustedProxyHops = 1) {
                val asAdmin: HttpRequestBuilder.() -> Unit = {
                    header(HttpHeaders.Authorization, admin.bearer)
                    header(HttpHeaders.XForwardedFor, IP)
                }
                fun HttpRequestBuilder.json(body: String) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                val code = panel.paidOrders.paid(sara.user.id, nila[0])
                with(shippedOrders) {
                    photographed(code)
                    approved(code, sara)
                }
                val noted = client.post("$API_V1/admin/orders/${code.value}/notes") {
                    asAdmin()
                    json("""{"text":"$NOTE"}""")
                }
                assertEquals(HttpStatusCode.Created, noted.status, noted.bodyAsText())
                val shipped = with(shippedOrders) { ship(code, """{"carrier":"POST","trackingCode":"PT-6000001"}""") }
                assertEquals(HttpStatusCode.OK, shipped.status, shipped.bodyAsText())
                val followed = client.post("$API_V1/tracking/lookup") {
                    header(HttpHeaders.XForwardedFor, IP)
                    json("""{"orderCode":"${code.value}","phone":"${recipient.recipientPhone}"}""")
                }
                assertEquals(HttpStatusCode.OK, followed.status, followed.bodyAsText())
                val missed = client.post("$API_V1/tracking/lookup") {
                    header(HttpHeaders.XForwardedFor, IP)
                    json("""{"orderCode":"${code.value}","phone":"$STRANGER"}""")
                }
                assertEquals(HttpStatusCode.NotFound, missed.status)
                assertEquals(HttpStatusCode.OK, client.get("$API_V1/tracking/daily?date=2026-10-12&q=${recipient.recipientPhone.takeLast(4)}").status)
                assertEquals(HttpStatusCode.OK, client.get("$API_V1/me/tracking") { header(HttpHeaders.Authorization, sara.bearer) }.status)
                clock.advance(Duration.ofHours(30))
                val delivered = client.post("$API_V1/admin/orders/${code.value}/status") {
                    header(HttpHeaders.Authorization, panel.caller(PanelTestStack.phone(952), Role.ADMIN).bearer)
                    header(HttpHeaders.XForwardedFor, IP)
                    json("""{"status":"DELIVERED","note":"$STATUS_NOTE"}""")
                }
                assertEquals(HttpStatusCode.OK, delivered.status, delivered.bodyAsText())

                val customer = panel.caller(PanelTestStack.phone(951))
                val staff = panel.caller(PanelTestStack.phone(952), Role.ADMIN)
                val dropped = panel.paidOrders.paid(sara.user.id, nila[1])
                val cancelled = client.post("$API_V1/me/orders/${dropped.value}/cancel") {
                    header(HttpHeaders.Authorization, customer.bearer)
                    json("""{"reason":"OTHER","note":"$CANCEL_NOTE"}""")
                }
                assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
                val refund = cancelled.json().getValue("refund").jsonObject.getValue("id").jsonPrimitive.content
                val completed = client.patch("$API_V1/admin/refunds/$refund") {
                    header(HttpHeaders.Authorization, staff.bearer)
                    header(HttpHeaders.XForwardedFor, IP)
                    json("""{"status":"COMPLETED","referenceId":"$BANK_REFERENCE","note":"$REFUND_NOTE"}""")
                }
                assertEquals(HttpStatusCode.OK, completed.status, completed.bodyAsText())
                assertEquals(HttpStatusCode.OK, client.get("$API_V1/admin/orders/${dropped.value}") { header(HttpHeaders.Authorization, staff.bearer) }.status)
            }
        }

        assertTrue(log.any { it.loggerName == "Exposed" }, "the SQL log was caught")
        assertTrue(log.any { "ShipOrderUc" in it.loggerName }, "the use cases' own lines were caught")
        val phones = listOf(sara.user.phone.value, recipient.recipientPhone, MESSENGER_PHONE, STRANGER)
        val leaks = leaksIn(log, phones, texts = listOf(IP, recipient.recipientName, NOTE, STATUS_NOTE, CANCEL_NOTE, BANK_REFERENCE, REFUND_NOTE))
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n") { (what, line) -> "$what in: $line" })
    }

    @Test
    fun `the scan finds a whole number, a name and a reference, so a pass means something`() {
        val log = logOf {
            val logger = LoggerFactory.getLogger(PanelOrdersLogScanOnDatabaseTest::class.java)
            logger.info("recipient 0912-345 6789 is ${PaidOrders.Destination.TEHRAN.recipientName}")
            logger.warn("refund reference $BANK_REFERENCE")
        }

        assertEquals(
            listOf("a whole phone number", "a personal text", "a personal text"),
            leaksIn(log, listOf(PaidOrders.Destination.TEHRAN.recipientPhone), listOf(PaidOrders.Destination.TEHRAN.recipientName, BANK_REFERENCE)).map { it.first },
        )
    }

    /** (what leaked, the line it is on) for each one found; empty when the log is clean. */
    private fun leaksIn(log: List<ILoggingEvent>, phones: List<String>, texts: List<String>): List<Pair<String, String>> {
        val lines = log.map { it.text() }
        // A phone in any form (09…, 9…, +98 9…) has these 10 digits in a row; a masked one doesn't.
        val wholeNumbers = phones.map { it.drop(1) }
        return lines.filter { line -> wholeNumbers.any { it in line.withoutDigitGaps() } }.map { "a whole phone number" to it } +
            lines.flatMap { line -> texts.filter { it.isNotEmpty() && it in line }.map { "a personal text" to line } }
    }

    private fun String.withoutDigitGaps(): String = replace(Regex("(?<=\\d)[ \\-]+(?=\\d)"), "")

    private fun ILoggingEvent.text(): String = buildString {
        append(formattedMessage)
        throwableProxy?.let { append('\n').append(ThrowableProxyUtil.asString(it)) }
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

    private companion object {
        const val IP = "203.0.113.91"
        const val NOTE = "مشتری گفت زنگ نزنید ساعت نه"
        const val STATUS_NOTE = "به نگهبان ساختمان تحویل شد"
        const val CANCEL_NOTE = "پول لازم شدم فوری"
        const val REFUND_NOTE = "واریز به کارت ملت"
        const val BANK_REFERENCE = "73519086"
        const val STRANGER = "09351112244"

        // PaidOrders' messenger number.
        const val MESSENGER_PHONE = "09121112233"
    }
}
