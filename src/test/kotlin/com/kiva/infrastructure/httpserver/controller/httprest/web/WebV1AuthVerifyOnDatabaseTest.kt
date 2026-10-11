package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.auth0.jwt.JWT
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OtpChallengeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefreshTokenTbl
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
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * POST /auth/otp/verify over the real stack on kiva_test: a code sent through POST /auth/otp/send, tried, and a session
 * made of it. Step 5 of the login plan is done when these pass.
 *
 * Its own far-off clock, so no other test's codes are in its windows (the JWT test uses the real time instead, so the
 * verifier's own expiry check can run). Test phones start with 0990; their sessions, codes and accounts are deleted
 * after each test. Security events can't be deleted, so the ones looked at here are told apart by phone and time.
 */
class WebV1AuthVerifyOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2033-05-01T08:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers()

    private fun withApp(stack: AuthTestStack = this.stack, block: suspend ApplicationTestBuilder.() -> Unit) = stack.app(block = block)

    private suspend fun ApplicationTestBuilder.sendCode(phone: String, stack: AuthTestStack = this@WebV1AuthVerifyOnDatabaseTest.stack): OtpCode {
        val response = client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return stack.lastCodeSentTo(phone)
    }

    private suspend fun ApplicationTestBuilder.verify(phone: String, code: String, more: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"$code"}""")
            more()
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String? = json()["code"]?.jsonPrimitive?.content

    /** Any code but [right]. */
    private fun wrong(right: OtpCode): String = ((right.value.toInt() + 1) % 100_000).toString().padStart(5, '0')

    /** The failed attempts of the phone's codes, oldest first, as the database has them. */
    private fun failedAttemptsInDatabase(phone: String): List<Int> = transaction(db) {
        OtpChallengeTbl.selectAll().where { OtpChallengeTbl.phone eq phone }.orderBy(OtpChallengeTbl.id)
            .map { it[OtpChallengeTbl.failedAttempts].toInt() }
    }

    private fun sessionsOf(phone: String) = transaction(db) {
        val userId = UserTbl.selectAll().where { UserTbl.phone eq phone }.singleOrNull()?.get(UserTbl.id)?.value ?: return@transaction emptyList()
        AuthSessionTbl.selectAll().where { AuthSessionTbl.userId eq userId }.toList()
    }

    private fun eventTypes(phone: String? = null, userId: Long? = null): List<String> = transaction(db) {
        SecurityEventTbl.selectAll()
            .where {
                val at = SecurityEventTbl.occurredOn eq clock.now.atOffset(ZoneOffset.UTC)
                when {
                    phone != null -> at and (SecurityEventTbl.phone eq phone)
                    else -> at and (SecurityEventTbl.userId eq userId)
                }
            }
            .orderBy(SecurityEventTbl.id)
            .map { it[SecurityEventTbl.type] }
    }

    @Test
    fun `a wrong code is 422 OTP_INVALID with 4 tries left, and the try is in the database, not rolled back`() = withApp {
        val phone = phone(1)
        val code = sendCode(phone)

        val first = verify(phone, wrong(code))

        assertEquals(HttpStatusCode.UnprocessableEntity, first.status)
        val body = first.json()
        assertEquals("OTP_INVALID", body["code"]?.jsonPrimitive?.content)
        assertEquals("4", body["meta"]?.jsonObject?.get("attemptsLeft")?.jsonPrimitive?.content)
        assertEquals(listOf(1), failedAttemptsInDatabase(phone))

        verify(phone, wrong(code))
        assertEquals(listOf(2), failedAttemptsInDatabase(phone))
        assertEquals(listOf("OTP_VERIFY_FAILED", "OTP_VERIFY_FAILED"), eventTypes(phone = phone).filter { it == "OTP_VERIFY_FAILED" })
    }

    @Test
    fun `the fifth wrong code is OTP_TOO_MANY_ATTEMPTS, and after it even the right code is`() = withApp {
        val phone = phone(2)
        val code = sendCode(phone)

        val answers = (1..5).map { verify(phone, wrong(code)).code() }
        val right = verify(phone, code.value)

        assertEquals(List(4) { "OTP_INVALID" } + "OTP_TOO_MANY_ATTEMPTS", answers)
        assertEquals(HttpStatusCode.UnprocessableEntity, right.status)
        assertEquals("OTP_TOO_MANY_ATTEMPTS", right.code())
        assertEquals(listOf(5), failedAttemptsInDatabase(phone))
        assertTrue(sessionsOf(phone).isEmpty())
    }

    @Test
    fun `the tenth wrong code in a day locks the phone, even for a fresh right code, and OTP_LOCKED is recorded`() = withApp {
        val phone = phone(3)
        val first = sendCode(phone)
        repeat(5) { verify(phone, wrong(first)) }
        clock.advance(Duration.ofSeconds(121))
        val second = sendCode(phone)
        repeat(4) { verify(phone, wrong(second)) }
        clock.advance(Duration.ofSeconds(121))
        val third = sendCode(phone)

        val tenth = verify(phone, wrong(third))
        val rightButLocked = verify(phone, third.value)

        assertEquals(HttpStatusCode.TooManyRequests, tenth.status)
        assertEquals("RATE_LIMITED", tenth.code())
        assertEquals(HttpStatusCode.TooManyRequests, rightButLocked.status)
        assertEquals("RATE_LIMITED", rightButLocked.code())
        // The first wrong codes were on the code sent 242 s ago; they leave the day in 24 h - 242 s.
        assertEquals("${Duration.ofHours(24).minusSeconds(242).seconds}", rightButLocked.headers[HttpHeaders.RetryAfter])
        assertEquals(listOf(5, 4, 1), failedAttemptsInDatabase(phone))
        assertTrue(sessionsOf(phone).isEmpty())
        assertEquals(1, eventTypes(phone = phone).count { it == "OTP_LOCKED" })
    }

    @Test
    fun `twenty logins with the right code at the same moment make one session, and the others are OTP_EXPIRED`() {
        val phone = phone(4)
        runBlocking { stack.send.execute(SendLoginCodeUc.Cmd(phone, purpose = null, ip = null)) }
        val code = stack.lastCodeSentTo(phone)

        val answers = atTheSameMoment(20) { stack.verify.execute(VerifyLoginCodeUc.Cmd(phone, code.value, null, null, null)) }

        assertEquals(1, answers.count { it.isSuccess })
        assertEquals(List(19) { "OTP_EXPIRED" }, answers.mapNotNull { it.failureOrNull()?.code })
        assertEquals(1, sessionsOf(phone).size)
    }

    @Test
    fun `twenty wrong codes at the same moment take exactly five tries`() {
        val phone = phone(5)
        runBlocking { stack.send.execute(SendLoginCodeUc.Cmd(phone, purpose = null, ip = null)) }
        val code = stack.lastCodeSentTo(phone)

        val answers = atTheSameMoment(20) { stack.verify.execute(VerifyLoginCodeUc.Cmd(phone, wrong(code), null, null, null)) }

        assertEquals(listOf(5), failedAttemptsInDatabase(phone))
        assertEquals(4, answers.count { it.failureOrNull()?.code == "OTP_INVALID" })
        assertEquals(16, answers.count { it.failureOrNull()?.code == "OTP_TOO_MANY_ATTEMPTS" })
    }

    private fun <T> atTheSameMoment(times: Int, block: suspend () -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(times)
        try {
            val calls = List(times) { pool.submit<T> { start.await(); runBlocking { block() } } }
            start.countDown()
            return calls.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a new phone gets its account as a customer, and its next login the same account`() = withApp {
        val phone = phone(6)

        val firstLogin = verify(phone, sendCode(phone).value).json()
        clock.advance(Duration.ofSeconds(121))
        val secondLogin = verify(phone, sendCode(phone).value).json()

        assertEquals("true", firstLogin["isNewUser"]?.jsonPrimitive?.content)
        assertEquals(listOf("CUSTOMER"), firstLogin["user"]?.jsonObject?.get("roles")?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("false", secondLogin["isNewUser"]?.jsonPrimitive?.content)
        assertEquals(firstLogin["user"]?.jsonObject?.get("id"), secondLogin["user"]?.jsonObject?.get("id"))
        assertEquals(2, sessionsOf(phone).size)
    }

    @Test
    fun `a code typed with Persian digits is accepted`() = withApp {
        val phone = phone(7)
        val persian = sendCode(phone).value.map { '۰' + (it - '0') }.joinToString("")

        assertEquals(HttpStatusCode.OK, verify(phone, persian).status)
    }

    @Test
    fun `the answer has the access token and the user, the refresh token only in an HttpOnly cookie for 30 days`() = withApp {
        val phone = phone(8)

        val response = verify(phone, sendCode(phone).value)

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertFalse("refreshToken" in body)
        assertEquals("Bearer", body["tokenType"]?.jsonPrimitive?.content)
        assertEquals("900", body["expiresIn"]?.jsonPrimitive?.content)
        assertEquals("2592000", body["refreshExpiresIn"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, body["cart"])
        val user = body["user"]?.jsonObject ?: error("no user")
        assertEquals(phone, user["phone"]?.jsonPrimitive?.content)
        assertEquals("دوست عزیز", user["displayName"]?.jsonPrimitive?.content)
        assertEquals("د", user["avatarInitial"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, user["fullName"])

        val cookie = response.headers.getAll(HttpHeaders.SetCookie)?.single() ?: error("no cookie")
        assertTrue(Regex("kiva_rt=[A-Za-z0-9_-]{43}; Max-Age=2592000; Path=/api/v1/auth; Secure; HttpOnly; SameSite=Strict").matches(cookie), cookie)
    }

    @Test
    fun `a staff member's session is a panel session, and its cookie lives 12 hours`() = withApp {
        val phone = phone(9)
        transaction(db) {
            val users = UserRepositoryImpl()
            val user = users.findOrRegister(IranianMobileNumber.constructFromPersisted(phone), clock.now).user
            users.save(user.setPanelRoles(listOf(Role.ADMIN), by = UserId.SYSTEM).getOrNull() ?: error("roles refused"))
        }

        val response = verify(phone, sendCode(phone).value)

        assertEquals("43200", response.json()["refreshExpiresIn"]?.jsonPrimitive?.content)
        assertTrue("Max-Age=43200;" in response.headers[HttpHeaders.SetCookie].orEmpty())
        assertEquals("PANEL", sessionsOf(phone).single()[AuthSessionTbl.kind].name)
    }

    @Test
    fun `the access token is an HS256 JWT of the user, the session and the roles, for 15 minutes`() {
        // At the real time: the verifier checks expiry against the real clock.
        val realTime = AuthTestStack(MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS)))
        realTime.app {
            val phone = phone(10)
            val accessToken = verify(phone, sendCode(phone, realTime).value).json()["accessToken"]?.jsonPrimitive?.content ?: error("no token")

            val jwt = realTime.accessTokens.verifier.verify(accessToken)
            val session = sessionsOf(phone).single()
            assertEquals("HS256", JWT.decode(accessToken).algorithm)
            assertEquals("kiva-api", jwt.issuer)
            assertEquals(listOf("kiva"), jwt.audience)
            assertEquals("${session[AuthSessionTbl.userId]}", jwt.subject)
            assertEquals("${session[AuthSessionTbl.id].value}", jwt.getClaim("sid").asString())
            assertEquals(listOf("CUSTOMER"), jwt.getClaim("roles").asList(String::class.java))
            assertEquals(jwt.issuedAtAsInstant.plusSeconds(900), jwt.expiresAtAsInstant)
        }
    }

    @Test
    fun `the tokens themselves are in no table, only the refresh token's SHA-256`() = withApp {
        val phone = phone(11)
        val response = verify(phone, sendCode(phone).value)
        val refreshToken = response.headers[HttpHeaders.SetCookie].orEmpty().substringAfter("kiva_rt=").substringBefore(';')
        val accessToken = response.json()["accessToken"]?.jsonPrimitive?.content ?: error("no token")

        assertEquals(43, refreshToken.length)
        assertEquals(emptyList(), tablesContaining(refreshToken))
        assertEquals(emptyList(), tablesContaining(accessToken))

        val sessionId = sessionsOf(phone).single()[AuthSessionTbl.id].value
        val storedHash = transaction(db) {
            RefreshTokenTbl.selectAll().where { RefreshTokenTbl.sessionId eq sessionId }.single()[RefreshTokenTbl.tokenHash]
        }
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray()), storedHash)
    }

    /** Every table of the database with a row whose text holds [value]. */
    private fun tablesContaining(value: String): List<String> = transaction(db) {
        val tables = exec("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'") { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }.orEmpty()
        tables.filter { table ->
            val found = exec("SELECT count(*) FROM \"$table\" t WHERE t::text LIKE ?", listOf(TextColumnType() to "%$value%")) {
                it.next()
                it.getLong(1)
            } ?: 0
            found > 0
        }
    }

    @Test
    fun `the session remembers its device and its two ends`() = withApp {
        val phone = phone(12)
        val code = sendCode(phone)

        client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14)")
            setBody("""{"phone":"$phone","code":"${code.value}","device":{"name":"Chrome on Android"}}""")
        }

        val session = sessionsOf(phone).single()
        assertEquals("CUSTOMER", session[AuthSessionTbl.kind].name)
        assertEquals("Chrome on Android", session[AuthSessionTbl.deviceName])
        assertEquals("Mozilla/5.0 (Linux; Android 14)", session[AuthSessionTbl.userAgent])
        assertNotNull(session[AuthSessionTbl.createdIp])
        assertEquals(clock.now.plus(Duration.ofDays(30)), session[AuthSessionTbl.idleExpiresOn].toInstant())
        assertEquals(clock.now.plus(Duration.ofDays(180)), session[AuthSessionTbl.absoluteExpiresOn].toInstant())
    }

    @Test
    fun `a login is recorded, and a first one records the new account too`() = withApp {
        val phone = phone(13)

        val userId = verify(phone, sendCode(phone).value).json()["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.toLong()

        assertEquals(listOf("USER_REGISTERED", "LOGIN_SUCCEEDED"), eventTypes(userId = userId))
    }

    @Test
    fun `a phone that was sent no code, or whose code was used, is OTP_EXPIRED`() = withApp {
        val phone = phone(14)
        assertEquals("OTP_EXPIRED", verify(phone, "12345").code())

        val code = sendCode(phone)
        verify(phone, code.value)
        val again = verify(phone, code.value)

        assertEquals(HttpStatusCode.UnprocessableEntity, again.status)
        assertEquals("OTP_EXPIRED", again.code())
    }

    @Test
    fun `a code after its five minutes is OTP_EXPIRED`() = withApp {
        val phone = phone(15)
        val code = sendCode(phone)
        clock.advance(Duration.ofMinutes(5).plusSeconds(1))

        assertEquals("OTP_EXPIRED", verify(phone, code.value).code())
    }

    @Test
    fun `a code in the wrong format is 400 on the code field and takes no try`() = withApp {
        val phone = phone(16)
        sendCode(phone)

        val response = verify(phone, "12a45")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("code", response.json()["errors"]?.jsonArray?.single()?.jsonObject?.get("field")?.jsonPrimitive?.content)
        assertEquals(listOf(0), failedAttemptsInDatabase(phone))
    }
}
