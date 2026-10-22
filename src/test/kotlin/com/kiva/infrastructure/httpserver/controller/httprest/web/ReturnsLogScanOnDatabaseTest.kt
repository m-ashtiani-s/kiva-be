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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 7.6 (roadmap section 3, row 15): a return's whole way with every log line caught, at the app's levels and with
 * the SQL log on. One return asked with a photo and a description, approved with staff's instructions, collected,
 * received with an internal note and refunded with the bank's reference; another turned down with a reason; a third
 * closed with one.
 *
 * No line may carry a whole mobile number, the customer's description, staff's instructions, reasons or notes, the
 * bank's reference, or the IP the requests came from.
 */
class ReturnsLogScanOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val returns = AskedReturns(panel, clock)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    @Test
    fun `a return asked, approved, collected, received and refunded, one turned down and one closed, leave nothing personal in the log`() {
        val sara = panel.caller(PanelTestStack.phone(961))
        val nila = listOf("black", "olive").map { panel.purchase.variantOf("nila-crossbody-bag", it) }

        val log = logOf {
            panel.app(trustedProxyHops = 1) {
                fun operator() = panel.caller(PanelTestStack.phone(962), Role.OPERATOR)
                fun admin() = panel.caller(PanelTestStack.phone(963), Role.ADMIN)
                suspend fun send(method: String, path: String, who: PanelTestStack.Caller, body: String): HttpResponse {
                    val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                        header(HttpHeaders.Authorization, who.bearer)
                        header(HttpHeaders.XForwardedFor, IP)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    val answer = when (method) {
                        "PUT" -> client.put("$API_V1$path", build)
                        "PATCH" -> client.patch("$API_V1$path", build)
                        else -> client.post("$API_V1$path", build)
                    }
                    assertTrue(answer.status.value in 200..299, "$method $path: ${answer.status} ${answer.bodyAsText()}")
                    return answer
                }

                // Refunded, the whole way.
                val code = with(returns) { delivered(sara, nila[0]) }
                val photo = with(returns) { photo(sara) }
                val asked = send(
                    "POST", "/me/orders/${code.value}/returns", returns.fresh(sara),
                    """{"items":[{"orderItemId":1,"quantity":1}],"reason":"NOT_AS_PICTURED","mediaIds":["$photo"],"description":"$DESCRIPTION","refundMethod":"BANK_TRANSFER"}""",
                )
                val id = Json.parseToJsonElement(asked.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                send("POST", "/admin/returns/$id/decision", operator(), """{"approve":true,"instructions":"$INSTRUCTIONS"}""")
                send("PUT", "/admin/returns/$id/pickup", operator(), """{"carrier":"TIPAX","trackingCode":"TX-555123","note":"$PICKUP_NOTE"}""")
                send("POST", "/admin/returns/$id/receipt", operator(), """{"items":[{"orderItemId":1,"receivedQuantity":1,"restockQuantity":1}],"note":"$RECEIPT_NOTE"}""")
                val refund = send("POST", "/admin/orders/${code.value}/refunds", admin(), """{"amount":1000,"reason":"$REFUND_REASON","returnId":$id}""")
                val refundId = Json.parseToJsonElement(refund.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                send("PATCH", "/admin/refunds/$refundId", admin(), """{"status":"COMPLETED","referenceId":"$BANK_REFERENCE","note":"$REFUND_NOTE"}""")
                assertEquals(HttpStatusCode.OK, client.get("$API_V1/me/returns") { header(HttpHeaders.Authorization, returns.fresh(sara).bearer) }.status)
                assertEquals(HttpStatusCode.OK, client.get("$API_V1/admin/returns") { header(HttpHeaders.Authorization, operator().bearer) }.status)

                // Turned down.
                val second = with(returns) { delivered(sara, nila[1]) }
                val turnedDown = with(returns) { asked(second, sara) }
                send("POST", "/admin/returns/$turnedDown/decision", operator(), """{"approve":false,"rejectionReason":"$REJECTION"}""")

                // Closed: the parcel never came.
                val third = with(returns) { delivered(sara, nila[0]) }
                val closed = with(returns) { asked(third, sara) }
                send("POST", "/admin/returns/$closed/decision", operator(), """{"approve":true}""")
                send("POST", "/admin/returns/$closed/close", operator(), """{"reason":"$CLOSE_REASON"}""")
            }
        }

        assertTrue(log.any { it.loggerName == "Exposed" }, "the SQL log was caught")
        assertTrue(log.any { "ReceiveReturnUc" in it.loggerName } && log.any { "CreateReturnUc" in it.loggerName }, "the use cases' own lines were caught")
        val texts = listOf(IP, DESCRIPTION, INSTRUCTIONS, PICKUP_NOTE, RECEIPT_NOTE, REFUND_REASON, BANK_REFERENCE, REFUND_NOTE, REJECTION, CLOSE_REASON)
        val leaks = leaksIn(log, listOf(sara.user.phone.value, PaidOrders.Destination.TEHRAN.recipientPhone), texts)
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n") { (what, line) -> "$what in: $line" })
    }

    @Test
    fun `the scan finds a whole number and a description, so a pass means something`() {
        val log = logOf {
            val logger = LoggerFactory.getLogger(ReturnsLogScanOnDatabaseTest::class.java)
            logger.info("return asked by 0912 345 6789: $DESCRIPTION")
        }

        assertEquals(listOf("a whole phone number", "a personal text"), leaksIn(log, listOf("09123456789"), listOf(DESCRIPTION)).map { it.first })
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
        const val IP = "203.0.113.97"
        const val DESCRIPTION = "رنگش با عکس سایت خیلی فرق داشت"
        const val INSTRUCTIONS = "با تیپاکس به انبار شهرری بفرست"
        const val PICKUP_NOTE = "مأمور ساعت ده تا دوازده میاد"
        const val RECEIPT_NOTE = "کیف سالم بود ولی بند خط افتاده"
        const val REFUND_REASON = "برگشت وجه مرجوعی نیلا"
        const val REFUND_NOTE = "واریز به کارت سامان"
        const val BANK_REFERENCE = "88213045"
        const val REJECTION = "کیف استفاده شده بود و برچسبش کنده شده"
        const val CLOSE_REASON = "مرسوله‌ای به انبار نرسید"
    }
}
