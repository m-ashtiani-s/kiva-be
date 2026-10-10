package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.auth0.jwt.JWT
import com.kiva.application.usecase.auth.usecase.command.RefreshSessionUc
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.ORIGIN
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefreshTokenTbl
import com.kiva.infrastructure.persistence.exposed.tbl.SecurityEventTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * POST /auth/refresh and POST /auth/logout over the real stack on kiva_test, with sessions made by real logins. Step 7
 * of the login plan is done when these pass.
 *
 * Its own far-off clock, moved by days to try a session's lifetimes. Test phones start with 0990; their sessions,
 * codes and accounts are deleted after each test.
 */
class WebV1AuthRefreshOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2035-01-01T09:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers()

    /** Logs [phone] in and gives back its refresh cookie. */
    private suspend fun ApplicationTestBuilder.login(phone: String): String {
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val response = client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"${stack.lastCodeSentTo(phone).value}"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.newCookie() ?: error("no cookie")
    }

    private suspend fun ApplicationTestBuilder.refresh(cookie: String?, origin: String? = ORIGIN): HttpResponse =
        client.post("$API_V1/auth/refresh") {
            if (origin != null) header(HttpHeaders.Origin, origin)
            if (cookie != null) header(HttpHeaders.Cookie, "kiva_rt=$cookie")
        }

    private suspend fun ApplicationTestBuilder.logout(cookie: String?, body: String? = null, origin: String? = ORIGIN): HttpResponse =
        client.post("$API_V1/auth/logout") {
            if (origin != null) header(HttpHeaders.Origin, origin)
            if (cookie != null) header(HttpHeaders.Cookie, "kiva_rt=$cookie")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    /** The value of the cookie this response sets, or null if it sets none (or clears it). */
    private fun HttpResponse.newCookie(): String? =
        headers.getAll(HttpHeaders.SetCookie)?.singleOrNull()?.substringAfter("kiva_rt=")?.substringBefore(';')?.ifEmpty { null }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String? = json()["code"]?.jsonPrimitive?.content

    private fun userIdOf(phone: String): Long = transaction(db) {
        UserTbl.selectAll().where { UserTbl.phone eq phone }.single()[UserTbl.id].value
    }

    private fun sessionsOf(phone: String) = transaction(db) {
        AuthSessionTbl.selectAll().where { AuthSessionTbl.userId eq userIdOf(phone) }.orderBy(AuthSessionTbl.id).toList()
    }

    private fun sha256(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())

    private fun events(type: String, phone: String) = transaction(db) {
        SecurityEventTbl.selectAll().where { (SecurityEventTbl.type eq type) and (SecurityEventTbl.userId eq userIdOf(phone)) }.toList()
    }

    @Test
    fun `a refresh rotates the token, so each session has one current token`() = stack.app {
        val phone = phone(1)
        val first = login(phone)
        clock.advance(Duration.ofMinutes(10))

        val response = refresh(first)

        assertEquals(HttpStatusCode.OK, response.status)
        val second = response.newCookie() ?: error("no new cookie")
        assertNotEquals(first, second)
        val body = response.json()
        assertNotNull(body["accessToken"]?.jsonPrimitive?.content)
        assertEquals("Bearer", body["tokenType"]?.jsonPrimitive?.content)
        assertEquals("900", body["expiresIn"]?.jsonPrimitive?.content)
        assertEquals("2592000", body["refreshExpiresIn"]?.jsonPrimitive?.content)
        assertTrue("Max-Age=2592000;" in response.headers[HttpHeaders.SetCookie].orEmpty())

        val session = sessionsOf(phone).single()
        val tokens = transaction(db) {
            RefreshTokenTbl.selectAll().where { RefreshTokenTbl.sessionId eq session[AuthSessionTbl.id].value }.orderBy(RefreshTokenTbl.id).toList()
        }
        assertEquals(clock.now, tokens.first()[RefreshTokenTbl.rotatedOn]?.toInstant())
        assertEquals(1, tokens.count { it[RefreshTokenTbl.rotatedOn] == null })
        assertContentEquals(sha256(second), tokens.single { it[RefreshTokenTbl.rotatedOn] == null }[RefreshTokenTbl.tokenHash])
        assertEquals(clock.now, session[AuthSessionTbl.lastUsedOn].toInstant())
        assertEquals(clock.now.plus(Duration.ofDays(30)), session[AuthSessionTbl.idleExpiresOn].toInstant())
    }

    @Test
    fun `the old token within 30 seconds is a second tab, an access token and no new cookie`() = stack.app {
        val first = login(phone(2))
        val second = refresh(first).newCookie() ?: error("no new cookie")
        clock.advance(Duration.ofSeconds(20))

        val secondTab = refresh(first)

        assertEquals(HttpStatusCode.OK, secondTab.status)
        assertNull(secondTab.headers[HttpHeaders.SetCookie])
        assertNotNull(secondTab.json()["accessToken"]?.jsonPrimitive?.content)
        assertEquals(HttpStatusCode.OK, refresh(second).status)
    }

    @Test
    fun `the old token after 30 seconds was copied, the session is revoked, the new token dies too, and it is recorded`() = stack.app {
        val phone = phone(3)
        val first = login(phone)
        val second = refresh(first).newCookie() ?: error("no new cookie")
        clock.advance(Duration.ofSeconds(31))

        val thief = refresh(first)

        assertEquals(HttpStatusCode.Unauthorized, thief.status)
        assertEquals("REFRESH_TOKEN_INVALID", thief.code())
        assertTrue("kiva_rt=; Max-Age=0;" in thief.headers[HttpHeaders.SetCookie].orEmpty())
        assertEquals(RevokeReason.REUSE_DETECTED, sessionsOf(phone).single()[AuthSessionTbl.revokeReason])
        assertEquals(HttpStatusCode.Unauthorized, refresh(second).status)
        val event = events("REFRESH_REUSE_DETECTED", phone).single()
        assertEquals("31", event[SecurityEventTbl.detail]["rotatedSecondsAgo"]?.jsonPrimitive?.content)
    }

    @Test
    fun `five refreshes with one cookie at the same moment all get an access token, and only one a new cookie`() {
        var first = ""
        stack.app { first = login(phone(4)) }

        val answers = atTheSameMoment(5) { stack.refresh.execute(RefreshSessionUc.Cmd(first, ip = null)) }

        assertTrue(answers.all { it.isSuccess }, "$answers")
        assertEquals(1, answers.count { it.getOrNull()?.refreshToken != null })
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

    /**
     * The session is read FOR UPDATE. A refresh that comes while a logout from all devices (or a role or phone change)
     * has revoked the session but not committed yet waits for it, then sees the revoke. Read without the lock, the
     * session would still look live, and the refresh's own save would write the revoke away.
     */
    @Test
    fun `a refresh that meets a revoke not committed yet waits for it, and never brings the session back`() {
        val phone = phone(18)
        var cookie = ""
        stack.app { cookie = login(phone) }
        val userId = UserId.constructFromPersisted(userIdOf(phone))
        val revoked = CountDownLatch(1)
        val mayCommit = CountDownLatch(1)
        var outcome = ""

        val logoutEverywhere = thread {
            transaction(db) {
                AuthSessionRepositoryImpl().revokeAllOf(userId, RevokeReason.LOGOUT_ALL, clock.now)
                revoked.countDown()
                mayCommit.await(30, TimeUnit.SECONDS)
            }
        }
        revoked.await(30, TimeUnit.SECONDS)
        val refresh = thread {
            outcome = runBlocking { stack.refresh.execute(RefreshSessionUc.Cmd(cookie, ip = null)) }.failureOrNull()?.code ?: "refreshed"
        }
        try {
            awaitSomeoneWaitingForALock()
        } finally {
            mayCommit.countDown()
            logoutEverywhere.join()
            refresh.join()
        }

        assertEquals("REFRESH_TOKEN_INVALID", outcome)
        assertEquals(RevokeReason.LOGOUT_ALL, sessionsOf(phone).single()[AuthSessionTbl.revokeReason])
    }

    private fun awaitSomeoneWaitingForALock() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            // A new transaction each time: within one, Postgres keeps showing the activity it saw first.
            val waiting = transaction(db) {
                exec("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'") {
                    it.next()
                    it.getInt(1)
                } ?: 0
            }
            if (waiting > 0) return
            Thread.sleep(20)
        }
        fail("the refresh never waited for the revoke")
    }

    @Test
    fun `a customer's session unused for 30 days and a second is gone`() = stack.app {
        val cookie = login(phone(5))
        clock.advance(Duration.ofDays(30).plusSeconds(1))

        assertEquals("REFRESH_TOKEN_INVALID", refresh(cookie).code())
    }

    @Test
    fun `a customer refreshing every 29 days stays logged in until day 180, and not after`() = stack.app {
        var cookie = login(phone(6))
        repeat(6) { round ->
            clock.advance(Duration.ofDays(29))
            val response = refresh(cookie)
            assertEquals(HttpStatusCode.OK, response.status, "day ${(round + 1) * 29}")
            cookie = response.newCookie() ?: error("no new cookie on day ${(round + 1) * 29}")
            // On day 174 the cookie lives only the 6 days left to day 180.
            if (round == 5) assertTrue("Max-Age=${Duration.ofDays(6).seconds};" in response.headers[HttpHeaders.SetCookie].orEmpty())
        }

        clock.advance(Duration.ofDays(6))

        assertEquals("REFRESH_TOKEN_INVALID", refresh(cookie).code())
    }

    @Test
    fun `a panel session lasts 12 hours unused, and 7 days however often it is refreshed`() = stack.app {
        listOf(phone(7), phone(8)).forEach { staff ->
            transaction(db) {
                val users = UserRepositoryImpl()
                val user = users.findOrRegister(IranianMobileNumber.constructFromPersisted(staff), clock.now).user
                users.save(user.setPanelRoles(listOf(Role.OPERATOR), by = UserId.SYSTEM).getOrNull() ?: error("refused"))
            }
        }
        val idle = login(phone(7))
        var busy = login(phone(8))
        suspend fun refreshBusy(atHour: Int) {
            busy = refresh(busy).newCookie() ?: error("refused at $atHour h")
        }

        clock.advance(Duration.ofHours(11))
        refreshBusy(11)
        clock.advance(Duration.ofHours(1).plusSeconds(1))
        assertEquals("REFRESH_TOKEN_INVALID", refresh(idle).code(), "the idle one, at 12 h 1 s")

        // The busy one refreshes every 11 hours: at 22, 33, ... 165 hours.
        clock.advance(Duration.ofHours(10).minusSeconds(1))
        refreshBusy(22)
        (3..15).forEach { round ->
            clock.advance(Duration.ofHours(11))
            refreshBusy(round * 11)
        }
        clock.advance(Duration.ofHours(3))
        assertEquals("REFRESH_TOKEN_INVALID", refresh(busy).code(), "the busy one, at 168 h")
    }

    @Test
    fun `refresh and logout answer only our frontends, so another site can't use the cookie`() = stack.app {
        val cookie = login(phone(9))

        listOf(null, "https://evil.example").forEach { origin ->
            val refreshed = refresh(cookie, origin = origin)
            assertEquals(HttpStatusCode.Forbidden, refreshed.status, "refresh from $origin")
            assertEquals("FORBIDDEN", refreshed.code())
            assertEquals(HttpStatusCode.Forbidden, logout(cookie, origin = origin).status, "logout from $origin")
        }

        // Nothing was rotated or ended by the refused calls.
        assertEquals(HttpStatusCode.OK, refresh(cookie).status)
    }

    @Test
    fun `refresh and logout take only a POST, so a link or an image on another page can't reach them`() = stack.app {
        val cookie = login(phone(17))

        listOf("/auth/refresh", "/auth/logout").forEach { path ->
            val response = client.get("$API_V1$path") {
                header(HttpHeaders.Origin, ORIGIN)
                header(HttpHeaders.Cookie, "kiva_rt=$cookie")
            }
            assertEquals(HttpStatusCode.MethodNotAllowed, response.status, "GET $path")
            assertNull(response.headers[HttpHeaders.SetCookie], "GET $path")
        }

        // Nothing was rotated or ended.
        assertEquals(HttpStatusCode.OK, refresh(cookie).status)
    }

    @Test
    fun `a logout ends the session, clears the cookie, and the next refresh is 401`() = stack.app {
        val phone = phone(10)
        val cookie = login(phone)

        val response = logout(cookie)

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("kiva_rt=; Max-Age=0; Path=/api/v1/auth; Secure; HttpOnly; SameSite=Strict", response.headers[HttpHeaders.SetCookie])
        assertEquals(RevokeReason.LOGOUT, sessionsOf(phone).single()[AuthSessionTbl.revokeReason])
        assertEquals(HttpStatusCode.Unauthorized, refresh(cookie).status)
        assertEquals(1, events("LOGOUT", phone).size)
    }

    @Test
    fun `logging out again, or without a cookie, is 204 too`() = stack.app {
        val cookie = login(phone(11))
        logout(cookie)

        assertEquals(HttpStatusCode.NoContent, logout(cookie).status)
        assertEquals(HttpStatusCode.NoContent, logout(cookie = null).status)
        assertEquals(HttpStatusCode.NoContent, logout(cookie = "not-a-token").status)
    }

    @Test
    fun `a logout needs no access token, so it works after that one expired`() = stack.app {
        val phone = phone(12)
        val cookie = login(phone)
        clock.advance(Duration.ofMinutes(20))

        assertEquals(HttpStatusCode.NoContent, logout(cookie).status)
        assertNotNull(sessionsOf(phone).single()[AuthSessionTbl.revokedOn])
    }

    @Test
    fun `a logout from all devices ends this user's sessions, and only this user's`() = stack.app {
        val mine = phone(13)
        val laptop = login(mine)
        clock.advance(Duration.ofSeconds(121))
        val phoneApp = login(mine)
        val someoneElse = login(phone(14))

        assertEquals(HttpStatusCode.NoContent, logout(laptop, body = """{"allDevices":true}""").status)

        assertEquals(HttpStatusCode.Unauthorized, refresh(phoneApp).status)
        assertEquals(HttpStatusCode.OK, refresh(someoneElse).status)
        assertEquals(listOf(RevokeReason.LOGOUT_ALL, RevokeReason.LOGOUT_ALL), sessionsOf(mine).map { it[AuthSessionTbl.revokeReason] })
        assertEquals("2", events("LOGOUT_ALL", mine).single()[SecurityEventTbl.detail]["sessionsEnded"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an old token past its grace only clears its cookie, it can't log the owner out`() = stack.app {
        val phone = phone(15)
        val old = login(phone)
        val current = refresh(old).newCookie() ?: error("no new cookie")
        clock.advance(Duration.ofSeconds(31))

        assertEquals(HttpStatusCode.NoContent, logout(old).status)

        assertNull(sessionsOf(phone).single()[AuthSessionTbl.revokedOn])
        assertEquals(HttpStatusCode.OK, refresh(current).status)
    }

    @Test
    fun `a refreshed access token carries the roles as they are now`() = stack.app {
        val phone = phone(16)
        val cookie = login(phone)
        transaction(db) {
            val users = UserRepositoryImpl()
            val user = users.findById(UserId.constructFromPersisted(userIdOf(phone))) ?: error("no user")
            users.save(user.setPanelRoles(listOf(Role.SUPPORT), by = UserId.SYSTEM).getOrNull() ?: error("refused"))
        }

        val accessToken = refresh(cookie).json()["accessToken"]?.jsonPrimitive?.content ?: error("no token")

        assertEquals(listOf("CUSTOMER", "SUPPORT"), JWT.decode(accessToken).getClaim("roles").asList(String::class.java))
    }
}
