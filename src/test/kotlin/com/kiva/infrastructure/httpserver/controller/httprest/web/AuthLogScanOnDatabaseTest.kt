package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.port.OtpCodes
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.model.valueobject.Salt
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.auth.HmacOtpCodes
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.JWT_SECRET
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.ORIGIN
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.OTP_PEPPER
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 11 of the login plan: everything an account goes through, with every log line caught, at the levels the app
 * logs at and with the SQL log on. No line may carry a code, a token, a cookie's value, a secret or a whole phone
 * number; a phone shows only masked, like 0990***0001.
 *
 * The codes are chosen, not random: each starts with 0, which no id, count or duration in a log does, so finding one
 * means a code leaked and never that some other number looks like it.
 */
class AuthLogScanOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-05-01T09:00:00Z"))
    private val codes = ChosenOtpCodes(HmacOtpCodes(OTP_PEPPER), 4817, 7352, 9164, 2583, 6039, 3971, 5208, 8645)
    private val stack = AuthTestStack(clock, otpCodes = codes)
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers()

    private class Session(val accessToken: String, val cookie: String)

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String? = null): HttpResponse =
        client.post("$API_V1$path") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.withCookie(path: String, cookie: String): HttpResponse =
        client.post("$API_V1$path") {
            header(HttpHeaders.Origin, ORIGIN)
            header(HttpHeaders.Cookie, "kiva_rt=$cookie")
        }

    private suspend fun HttpResponse.session(): Session {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return Session(
            accessToken = Json.parseToJsonElement(bodyAsText()).jsonObject["accessToken"]?.jsonPrimitive?.content ?: error("no token"),
            cookie = headers[HttpHeaders.SetCookie].orEmpty().substringAfter("kiva_rt=").substringBefore(';'),
        )
    }

    private suspend fun ApplicationTestBuilder.login(phone: String): Session {
        post("/auth/otp/send", """{"phone":"$phone"}""")
        return post("/auth/otp/verify", """{"phone":"$phone","code":"${code(phone)}"}""").session()
    }

    private fun code(phone: String) = stack.lastCodeSentTo(phone).value

    @Test
    fun `a login with a wrong try, a profile, a refresh and a logout leave nothing secret in the log`() {
        val sara = phone(1)
        val messenger = phone(2)
        val handedOut = mutableListOf<String>()

        val log = logOf {
            stack.app {
                post("/auth/otp/send", """{"phone":"$sara"}""")
                assertEquals(HttpStatusCode.UnprocessableEntity, post("/auth/otp/verify", """{"phone":"$sara","code":"$WRONG_CODE"}""").status)
                val login = post("/auth/otp/verify", """{"phone":"$sara","code":"${code(sara)}"}""").session()
                handedOut += listOf(login.accessToken, login.cookie)

                assertEquals(HttpStatusCode.OK, client.get("$API_V1/me") { header(HttpHeaders.Authorization, "Bearer ${login.accessToken}") }.status)
                val profile = client.patch("$API_V1/me") {
                    header(HttpHeaders.Authorization, "Bearer ${login.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"firstName":"سارا","email":"sara@example.com","defaultMessenger":"TELEGRAM","defaultMessengerPhone":"$messenger"}""")
                }
                assertEquals(HttpStatusCode.OK, profile.status, profile.bodyAsText())

                clock.advance(Duration.ofMinutes(16))
                val refreshed = withCookie("/auth/refresh", login.cookie).session()
                handedOut += listOf(refreshed.accessToken, refreshed.cookie)
                assertEquals(HttpStatusCode.NoContent, withCookie("/auth/logout", refreshed.cookie).status)
            }
        }

        assertNothingSecretIn(log, phones = listOf(sara, messenger), handedOut = handedOut)
        assertTrue(log.any { IranianMobileNumber.constructFromPersisted(sara).masked() in it.formattedMessage }, "the use cases' own lines were caught")
    }

    @Test
    fun `a phone change leaves neither its codes nor either whole number in the log`() {
        val old = phone(3)
        val new = phone(4)
        val handedOut = mutableListOf<String>()

        val log = logOf {
            stack.app {
                val login = login(old)
                handedOut += listOf(login.accessToken, login.cookie)

                assertEquals(HttpStatusCode.OK, post("/me/phone-change/request", """{"newPhone":"$new"}""", login.accessToken).status)
                val wrong = post("/me/phone-change/verify", """{"newPhone":"$new","currentPhoneCode":"$WRONG_CODE","newPhoneCode":"${code(new)}"}""", login.accessToken)
                assertEquals(HttpStatusCode.UnprocessableEntity, wrong.status)
                val moved = post("/me/phone-change/verify", """{"newPhone":"$new","currentPhoneCode":"${code(old)}","newPhoneCode":"${code(new)}"}""", login.accessToken).session()
                handedOut += listOf(moved.accessToken, moved.cookie)

                handedOut += withCookie("/auth/refresh", moved.cookie).session().let { listOf(it.accessToken, it.cookie) }
            }
        }

        assertNothingSecretIn(log, phones = listOf(old, new), handedOut = handedOut)
        assertTrue(stack.sms.notices.isNotEmpty(), "the old phone was warned, so that path was logged too")
    }

    @Test
    fun `an admin's search by a whole number and a login body that can't be read leave neither in the log`() {
        val customer = phone(5)
        val admin = phone(6)
        transaction(db) {
            val users = UserRepositoryImpl()
            val made = users.findOrRegister(IranianMobileNumber.constructFromPersisted(admin), clock.now).user
            users.save(made.setPanelRoles(listOf(Role.ADMIN), by = UserId.SYSTEM).getOrNull() ?: error("refused"))
        }
        val handedOut = mutableListOf<String>()

        val log = logOf {
            stack.app {
                handedOut += login(customer).let { listOf(it.accessToken, it.cookie) }
                val staff = login(admin)
                handedOut += listOf(staff.accessToken, staff.cookie)

                val search = client.get("$API_V1/admin/customers") {
                    header(HttpHeaders.Authorization, "Bearer ${staff.accessToken}")
                    parameter("q", customer)
                }
                assertEquals(HttpStatusCode.OK, search.status, search.bodyAsText())
                val broken = client.get("$API_V1/admin/customers") {
                    header(HttpHeaders.Authorization, "Bearer ${staff.accessToken}")
                    parameter("q", customer)
                    parameter("page", "first")
                }
                assertEquals(HttpStatusCode.BadRequest, broken.status)

                // Broken JSON: the reader's own message would quote the body, phone and code included.
                val unreadable = post("/auth/otp/verify", """{"phone":"$customer","code":"$WRONG_CODE",""")
                assertEquals(HttpStatusCode.BadRequest, unreadable.status)
            }
        }

        assertNothingSecretIn(log, phones = listOf(customer, admin), handedOut = handedOut)
    }

    @Test
    fun `the scan finds a whole number, a code and a token, so a pass means something`() {
        val log = logOf {
            val logger = LoggerFactory.getLogger(AuthLogScanOnDatabaseTest::class.java)
            logger.info("sent to +98 990 000 0007 the code $WRONG_CODE")
            logger.warn("refresh with kiva_rt=abc.def")
        }

        assertEquals(
            listOf("a whole phone number", "a code", "a token or cookie"),
            leaksIn(log, phones = listOf(phone(7)), codes = listOf(WRONG_CODE), handedOut = listOf("abc.def")).map { it.first },
        )
    }

    private fun assertNothingSecretIn(log: List<ILoggingEvent>, phones: List<String>, handedOut: List<String>) {
        assertTrue(log.any { it.loggerName == "Exposed" }, "the SQL log was caught")
        val sent = stack.sms.sent.map { it.second.value }
        assertEquals(sent.size, sent.toSet().size, "every code was its own")
        val leaks = leaksIn(log, phones, codes = sent + WRONG_CODE, handedOut = handedOut)
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n") { (what, line) -> "$what in: $line" })
    }

    /** (what leaked, the line it is on) for each secret found; empty when the log is clean. */
    private fun leaksIn(log: List<ILoggingEvent>, phones: List<String>, codes: List<String>, handedOut: List<String>): List<Pair<String, String>> {
        val lines = log.map { it.text() }
        // A phone in any form (09…, 9…, +98 9…) has these 10 digits in a row; a masked one doesn't.
        val wholeNumbers = phones.map { it.drop(1) }
        val wholeCodes = codes.map { Regex("(?<!\\d)$it(?!\\d)") }
        val tokens = (handedOut + SECRETS).filter { it.isNotEmpty() }
        return lines.filter { line -> wholeNumbers.any { it in line.withoutDigitGaps() } }.map { "a whole phone number" to it } +
            lines.filter { line -> wholeCodes.any { it.containsMatchIn(line) } }.map { "a code" to it } +
            lines.filter { line -> tokens.any { it in line } }.map { "a token or cookie" to it }
    }

    /** A phone written as `0990 000 0001` or `990-000-0001` is still a whole number: spaces and dashes between digits go. */
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

    /** Real salts and hashes, but codes from a list, in order. */
    private class ChosenOtpCodes(private val real: OtpCodes, vararg numbers: Int) : OtpCodes {
        private val next = ArrayDeque(numbers.map(OtpCode::of))

        override fun newCode(purpose: OtpPurpose, phone: IranianMobileNumber): OtpCodes.NewCode {
            val salt = real.newCode(purpose, phone).salt
            val code = synchronized(next) { next.removeFirst() }
            return OtpCodes.NewCode(code, salt, real.hash(code, salt, purpose, phone))
        }

        override fun hash(code: OtpCode, salt: Salt, purpose: OtpPurpose, phone: IranianMobileNumber) = real.hash(code, salt, purpose, phone)
    }

    companion object {
        /** A code that was never sent: what a typo or a guess looks like. Starts with 0 like the chosen ones. */
        private const val WRONG_CODE = "08261"

        private val SECRETS = listOf(OTP_PEPPER, JWT_SECRET).flatMap {
            listOf(Base64.getEncoder().encodeToString(it), Base64.getUrlEncoder().withoutPadding().encodeToString(it))
        }
    }
}
