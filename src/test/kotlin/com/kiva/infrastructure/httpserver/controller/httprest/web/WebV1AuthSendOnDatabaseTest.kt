package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.valueobject.CodeHash
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.model.valueobject.Salt
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.auth.HmacOtpCodes
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.OtpChallengeRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.OtpChallengeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.SecurityEventTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * POST /auth/otp/send over the real stack on kiva_test: route, use case, the limits counted from stored codes, the
 * phone lock, the limit per address. Step 4 of the login plan is done when these pass.
 *
 * Its own far-off clock, so no other test's codes are in its windows; every test phone starts with 0990, and those
 * codes and users are deleted after each test. Security events can't be deleted (that is their point), so the ones
 * looked at here are told apart by their time.
 */
class WebV1AuthSendOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2031-03-01T08:00:00Z"))
    private val sms = RecordingSmsSender()
    private val otpCodes = HmacOtpCodes(ByteArray(32) { 7 })

    private fun useCase(maxSmsPerHour: Int = 300) = SendLoginCodeUc(
        ExposedTransactionManager(db), OtpChallengeRepositoryImpl(), otpCodes, sms, SecurityEventLogImpl(), clock, maxSmsPerHour,
    )

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) {
            OtpChallengeTbl.deleteWhere { OtpChallengeTbl.phone like "0990%" }
            UserTbl.deleteWhere { UserTbl.phone like "0990%" }
        }
    }

    /** Remembers what it sent; fails when told to, like a provider that is down. */
    private class RecordingSmsSender : SmsSender {
        val sent: MutableList<Pair<IranianMobileNumber, OtpCode>> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var down = false

        override suspend fun sendCode(to: IranianMobileNumber, code: OtpCode, purpose: OtpPurpose) {
            check(!down) { "the SMS provider is down" }
            sent += to to code
        }
    }

    private fun withApp(useCase: SendLoginCodeUc = useCase(), trustedProxyHops: Int = 0, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureHttp(trustedProxyHops = trustedProxyHops)
                routing { route(API_V1) { webV1Auth(useCase) } }
            }
            block()
        }

    private suspend fun ApplicationTestBuilder.send(phone: String, body: String = """{"phone":"$phone"}""", more: HttpRequestBuilder.() -> Unit = {}) =
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody(body)
            more()
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun phone(n: Int) = "0990" + n.toString().padStart(7, '0')

    private data class StoredCode(val salt: Salt, val hash: CodeHash, val open: Boolean, val requestIp: String?)

    private fun storedCodes(phone: String): List<StoredCode> = transaction(db) {
        OtpChallengeTbl.selectAll().where { OtpChallengeTbl.phone eq phone }.orderBy(OtpChallengeTbl.id).map {
            StoredCode(
                Salt(it[OtpChallengeTbl.salt]),
                CodeHash(it[OtpChallengeTbl.codeHash]),
                open = it[OtpChallengeTbl.consumedOn] == null && it[OtpChallengeTbl.replacedOn] == null,
                requestIp = it[OtpChallengeTbl.requestIp],
            )
        }
    }

    @Test
    fun `a phone with an account and one without get the same answer, but for the phone itself`() = withApp {
        val registered = phone(1)
        val unknown = phone(2)
        transaction(db) { UserRepositoryImpl().findOrRegister(IranianMobileNumber.constructFromPersisted(registered), clock.now) }

        val forRegistered = send(registered)
        val forUnknown = send(unknown)

        assertEquals(HttpStatusCode.OK, forRegistered.status)
        assertEquals(forRegistered.status, forUnknown.status)
        assertEquals(forRegistered.headers[HttpHeaders.ContentType], forUnknown.headers[HttpHeaders.ContentType])
        assertEquals(forRegistered.bodyAsText().replace(registered, "PHONE"), forUnknown.bodyAsText().replace(unknown, "PHONE"))
        assertEquals(
            """{"phone":"$unknown","codeLength":5,"expiresInSeconds":300,"resendAvailableInSeconds":120}""",
            forUnknown.bodyAsText(),
        )
    }

    @Test
    fun `the phone is answered in its stored form, however it was typed`() = withApp {
        val response = send("+98 990 000 0003")

        assertEquals(phone(3), response.json()["phone"]?.jsonPrimitive?.content)
        assertEquals(1, storedCodes(phone(3)).size)
    }

    @Test
    fun `the stored code is a salted hash of the code in the SMS, never the code itself`() = withApp {
        send(phone(4))

        val (to, code) = sms.sent.single()
        val stored = storedCodes(phone(4)).single()
        assertEquals(phone(4), to.value)
        assertTrue(stored.hash.matches(otpCodes.hash(code, stored.salt, OtpPurpose.LOGIN, to)))
        assertTrue(stored.open)
        assertNotNull(stored.requestIp)
    }

    @Test
    fun `a second code within 120 seconds is 429 OTP_RESEND_TOO_SOON, the wait in the header and the meta alike`() = withApp {
        send(phone(5))
        clock.advance(Duration.ofSeconds(30))

        val again = send(phone(5))

        assertEquals(HttpStatusCode.TooManyRequests, again.status)
        val body = again.json()
        assertEquals("OTP_RESEND_TOO_SOON", body["code"]?.jsonPrimitive?.content)
        assertEquals("90", body["meta"]?.jsonObject?.get("retryAfterSeconds")?.jsonPrimitive?.content)
        assertEquals("90", again.headers[HttpHeaders.RetryAfter])
        assertEquals("۹۰ ثانیه دیگه می‌تونی دوباره کد بگیری", body["message"]?.jsonPrimitive?.content)
        assertEquals(1, sms.sent.size)
        assertEquals(1, storedCodes(phone(5)).size)
    }

    @Test
    fun `a new code replaces the open one, so only one is open`() = withApp {
        send(phone(6))
        clock.advance(Duration.ofSeconds(121))

        assertEquals(HttpStatusCode.OK, send(phone(6)).status)

        assertEquals(listOf(false, true), storedCodes(phone(6)).map { it.open })
        assertEquals(2, sms.sent.size)
    }

    @Test
    fun `the sixth code in an hour is 429 RATE_LIMITED`() = withApp {
        repeat(5) {
            assertEquals(HttpStatusCode.OK, send(phone(7)).status, "code ${it + 1}")
            clock.advance(Duration.ofSeconds(121))
        }

        val sixth = send(phone(7))

        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertEquals("RATE_LIMITED", sixth.json()["code"]?.jsonPrimitive?.content)
        // The first of the five was sent 5 × 121 s ago; it leaves the hour in 3600 - 605 s.
        assertEquals("2995", sixth.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `the eleventh code in a day is 429 RATE_LIMITED, though no hour had more than five`() = withApp {
        repeat(10) {
            assertEquals(HttpStatusCode.OK, send(phone(8)).status, "code ${it + 1}")
            clock.advance(Duration.ofMinutes(13))
        }

        val eleventh = send(phone(8))

        assertEquals(HttpStatusCode.TooManyRequests, eleventh.status)
        assertEquals("RATE_LIMITED", eleventh.json()["code"]?.jsonPrimitive?.content)
        // The first was sent 130 minutes ago; it leaves the day in 24 h - 130 min.
        assertEquals("${Duration.ofHours(24).minusMinutes(130).seconds}", eleventh.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `ten sends to one phone at the same moment send one SMS`() {
        val useCase = useCase()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        val results = try {
            val sends = List(10) {
                pool.submit<Boolean> {
                    start.await()
                    runBlocking { useCase.execute(SendLoginCodeUc.Cmd(phone(9), purpose = null, ip = "203.0.113.9")).isSuccess }
                }
            }
            start.countDown()
            sends.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        assertEquals(1, results.count { it })
        assertEquals(1, sms.sent.size)
        assertEquals(1, storedCodes(phone(9)).size)
    }

    @Test
    fun `the 21st request from one address in ten minutes is 429, and a made-up X-Forwarded-For changes nothing`() = withApp {
        (1..20).forEach { assertEquals(HttpStatusCode.OK, send(phone(100 + it)).status, "request $it") }

        val forged = send(phone(121)) { header(HttpHeaders.XForwardedFor, "198.51.100.77") }

        assertEquals(HttpStatusCode.TooManyRequests, forged.status)
        assertEquals("RATE_LIMITED", forged.json()["code"]?.jsonPrimitive?.content)
        assertNotNull(forged.headers[HttpHeaders.RetryAfter])
        assertTrue(storedCodes(phone(121)).isEmpty())
    }

    @Test
    fun `behind our nginx the address is the last X-Forwarded-For entry, the one nginx wrote`() = withApp(trustedProxyHops = 1) {
        // The first entries are whatever the client sent; only the last one counts.
        (1..20).forEach { send(phone(200 + it)) { header(HttpHeaders.XForwardedFor, "10.9.9.$it, 192.0.2.10") } }

        val sameClient = send(phone(221)) { header(HttpHeaders.XForwardedFor, "10.9.9.99, 192.0.2.10") }
        val otherClient = send(phone(222)) { header(HttpHeaders.XForwardedFor, "192.0.2.11") }

        assertEquals(HttpStatusCode.TooManyRequests, sameClient.status)
        assertEquals(HttpStatusCode.OK, otherClient.status)
        assertEquals("192.0.2.11", storedCodes(phone(222)).single().requestIp)
    }

    @Test
    fun `at the shop's hourly cap no code goes out, with a 429, an ERROR in the log and a security event`() {
        val capReachedOn = Instant.parse("2032-06-01T10:00:00Z")
        clock.advance(Duration.between(clock.now, capReachedOn))
        val errors = errorsLoggedBy(SendLoginCodeUc::class.java) {
            withApp(useCase(maxSmsPerHour = 3)) {
                (1..3).forEach { assertEquals(HttpStatusCode.OK, send(phone(300 + it)).status) }

                val fourth = send(phone(304))

                assertEquals(HttpStatusCode.TooManyRequests, fourth.status)
                assertEquals("RATE_LIMITED", fourth.json()["code"]?.jsonPrimitive?.content)
                assertEquals("3600", fourth.headers[HttpHeaders.RetryAfter])
            }
        }

        assertEquals(1, errors.size)
        assertTrue("cap of 3 SMS" in errors.single().formattedMessage)
        val event = transaction(db) {
            SecurityEventTbl.selectAll()
                .where { (SecurityEventTbl.type eq "SMS_HOURLY_CAP_REACHED") and (SecurityEventTbl.occurredOn eq capReachedOn.atOffset(ZoneOffset.UTC)) }
                .single()
        }
        assertEquals("3", event[SecurityEventTbl.detail]["cap"]?.jsonPrimitive?.content)
        assertTrue(storedCodes(phone(304)).isEmpty())
        assertEquals(3, sms.sent.size)
    }

    @Test
    fun `when the SMS can't be sent the answer is 500, and the stored code still counts`() = withApp {
        sms.down = true
        assertEquals(HttpStatusCode.InternalServerError, send(phone(10)).status)
        assertEquals(1, storedCodes(phone(10)).size)

        sms.down = false
        assertEquals("OTP_RESEND_TOO_SOON", send(phone(10)).json()["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a wrong phone is 400 on the phone field, and nothing is stored or sent`() = withApp {
        val response = send("0912")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.json()
        assertEquals("VALIDATION_ERROR", body["code"]?.jsonPrimitive?.content)
        assertEquals("phone", body["errors"]?.jsonArray?.single()?.jsonObject?.get("field")?.jsonPrimitive?.content)
        assertTrue(sms.sent.isEmpty())
    }

    @Test
    fun `a purpose other than LOGIN is 400 on the purpose field`() = withApp {
        val response = send(phone(11), body = """{"phone":"${phone(11)}","purpose":"PHONE_CHANGE_NEW"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("purpose", response.json()["errors"]?.jsonArray?.single()?.jsonObject?.get("field")?.jsonPrimitive?.content)
        assertTrue(storedCodes(phone(11)).isEmpty())
    }

    private fun errorsLoggedBy(type: Class<*>, block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(type) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.ERROR }
    }
}
