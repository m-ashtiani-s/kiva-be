package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.CART_TOKEN_HEADER
import com.kiva.infrastructure.httpserver.IDEMPOTENCY_KEY_HEADER
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.HttpClient
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 21 of the purchase roadmap (section 19, «لاگ»): a whole purchase with every log line caught, at the levels the
 * app logs at and with the SQL log on: a guest's cart, the login that merges it, a new address, the order with a
 * messenger, a note and a gift message, the pretend bank, the callback, the result page and the order's page.
 *
 * No line may carry the address's text, its postal code, the recipient's name, a whole mobile number (the customer's,
 * the recipient's or the messenger's), the messenger's id or note, the order's note or gift message, or the cart's
 * token. Each value is chosen so it can't appear by chance.
 */
class PurchaseLogScanOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val purchase = PurchaseTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        purchase.deleteWhatWasStored()
        seeded.deleteSeeded()
        others.deleteAll()
    }

    private val nilaOlive by lazy { purchase.variantOf("nila-crossbody-bag", "olive") }
    private val termehLilac by lazy { purchase.variantOf("termeh-wallet", "lilac") }

    @Test
    fun `a guest's cart, the login that merges it, a new address, an order, the bank, the callback and the pages leave nothing personal in the log`() {
        val customer = phone(3101)
        var cartToken = ""
        var paymentId = ""

        val log = logOf {
            purchase.app {
                val first = client.post("$API_V1/cart/items") { json("""{"variantId":$nilaOlive,"quantity":1}""") }
                assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
                cartToken = first.headers[CART_TOKEN_HEADER]!!
                val second = client.post("$API_V1/cart/items") {
                    header(CART_TOKEN_HEADER, cartToken)
                    json("""{"variantId":$termehLilac,"quantity":1}""")
                }
                assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())

                client.post("$API_V1/auth/otp/send") { json("""{"phone":"$customer"}""") }
                val login = client.post("$API_V1/auth/otp/verify") {
                    json("""{"phone":"$customer","code":"${purchase.auth.lastCodeSentTo(customer).value}","guestCartToken":"$cartToken"}""")
                }
                assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
                val bearer = login.json()["accessToken"]!!.jsonPrimitive.content

                val checkout = client.get("$API_V1/checkout") { header(HttpHeaders.Authorization, "Bearer $bearer") }
                assertEquals(HttpStatusCode.OK, checkout.status, checkout.bodyAsText())

                val placed = client.post("$API_V1/orders") {
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                    header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    json(
                        """{"newAddress":{"provinceId":8,"cityId":394,"addressLine":"$ADDRESS_LINE","plaque":"۴۷","unit":"۹","postalCode":"$POSTAL_CODE",""" +
                            """"recipientName":"$RECIPIENT","recipientPhone":"$RECIPIENT_PHONE"},"saveNewAddress":true,""" +
                            """"preShipmentMessenger":{"channel":"TELEGRAM","phone":"$MESSENGER_PHONE","username":"$MESSENGER_ID","note":"$MESSENGER_NOTE","saveAsDefault":true},""" +
                            """"shippingMethod":"POST","paymentGateway":"ZARINPAL","customerNote":"$CUSTOMER_NOTE","gift":{"message":"$GIFT_MESSAGE","hidePrices":true},""" +
                            """"expectedPayable":2115000,"acceptTerms":true}""",
                    )
                }
                assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
                val order = placed.json()
                paymentId = order["payment"]!!.jsonObject["paymentId"]!!.jsonPrimitive.content
                val code = order["order"]!!.jsonObject["code"]!!.jsonPrimitive.content

                val authority = transaction(purchase.db) {
                    PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.authority]!!
                }
                assertTrue(purchase.gateway.pay(authority))
                clock.advance(Duration.ofMinutes(1))
                val back = browser().get("$API_V1/payment-callbacks/zarinpal?Authority=$authority&Status=OK")
                assertEquals(HttpStatusCode.Found, back.status)

                val result = client.get("$API_V1/payments/$paymentId") { header(HttpHeaders.Authorization, "Bearer $bearer") }
                assertEquals(HttpStatusCode.OK, result.status, result.bodyAsText())
                assertEquals("SUCCEEDED", result.json()["status"]!!.jsonPrimitive.content)
                val page = client.get("$API_V1/me/orders/$code") { header(HttpHeaders.Authorization, "Bearer $bearer") }
                assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
                assertTrue(POSTAL_CODE in page.bodyAsText(), "the order's page shows the address, so it went through every layer")
                assertEquals(HttpStatusCode.OK, client.get("$API_V1/me/addresses") { header(HttpHeaders.Authorization, "Bearer $bearer") }.status)
            }
        }

        assertTrue(log.any { it.loggerName == "Exposed" }, "the SQL log was caught")
        assertTrue(log.any { paymentId in it.formattedMessage }, "the payment's own lines were caught")
        val leaks = leaksIn(log, phones = listOf(customer, RECIPIENT_PHONE, MESSENGER_PHONE), texts = PERSONAL_TEXTS + cartToken)
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n") { (what, line) -> "$what in: $line" })
    }

    @Test
    fun `the scan finds a whole number, an address and a cart token, so a pass means something`() {
        val log = logOf {
            val logger = LoggerFactory.getLogger(PurchaseLogScanOnDatabaseTest::class.java)
            logger.info("recipient 0912 765 4321 at $ADDRESS_LINE")
            logger.warn("cart gct_abc")
        }

        assertEquals(
            listOf("a whole phone number", "a personal text", "a personal text"),
            leaksIn(log, phones = listOf(RECIPIENT_PHONE), texts = listOf(ADDRESS_LINE, "gct_abc")).map { it.first },
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

    /** The shopper's browser back from the gateway: it doesn't follow the callback's redirect to the frontend. */
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { followRedirects = false }

    private fun io.ktor.client.request.HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private companion object {
        const val ADDRESS_LINE = "خیابان شهید بهشتی، کوچه یاس‌نهم"
        const val POSTAL_CODE = "1587694321"
        const val RECIPIENT = "نیلوفر کاظم‌زاده"
        const val RECIPIENT_PHONE = "09127654321"
        const val MESSENGER_PHONE = "09351239876"
        const val MESSENGER_ID = "@niloo_kz97"
        const val MESSENGER_NOTE = "از بند و زیپش هم عکس بگیرید"
        const val CUSTOMER_NOTE = "عصرها زنگ بزنید لطفاً"
        const val GIFT_MESSAGE = "تولدت مبارک نیلوجان"

        val PERSONAL_TEXTS = listOf(
            ADDRESS_LINE, "یاس‌نهم", POSTAL_CODE, RECIPIENT, "کاظم‌زاده", MESSENGER_ID, MESSENGER_NOTE, CUSTOMER_NOTE, GIFT_MESSAGE,
        )
    }
}
