package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.auth0.jwt.JWT
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.ORIGIN
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OtpChallengeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.SecurityEventTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * POST /me/phone-change/request and /verify over kiva_test, with accounts that really logged in. Step 10 of the login
 * plan is done when these pass. Test phones start with 0990 and are deleted after each test.
 */
class WebV1PhoneChangeOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-03-01T09:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers()

    private class Login(val userId: Long, val accessToken: String, val cookie: String, val isNewUser: Boolean)

    private suspend fun ApplicationTestBuilder.login(phone: String): Login {
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val response = client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"${stack.lastCodeSentTo(phone).value}"}""")
        }
        val body = response.json()
        return Login(
            userId = body["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.toLong() ?: error("no user"),
            accessToken = body["accessToken"]?.jsonPrimitive?.content ?: error("no token"),
            cookie = response.headers[HttpHeaders.SetCookie].orEmpty().substringAfter("kiva_rt=").substringBefore(';'),
            isNewUser = body["isNewUser"]?.jsonPrimitive?.content == "true",
        )
    }

    private suspend fun ApplicationTestBuilder.request(token: String, newPhone: String): HttpResponse =
        client.post("$API_V1/me/phone-change/request") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"newPhone":"$newPhone"}""")
        }

    private suspend fun ApplicationTestBuilder.verify(token: String, newPhone: String, currentCode: String, newCode: String): HttpResponse =
        client.post("$API_V1/me/phone-change/verify") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"newPhone":"$newPhone","currentPhoneCode":"$currentCode","newPhoneCode":"$newCode"}""")
        }

    private suspend fun ApplicationTestBuilder.refresh(cookie: String): HttpResponse =
        client.post("$API_V1/auth/refresh") {
            header(HttpHeaders.Origin, ORIGIN)
            header(HttpHeaders.Cookie, "kiva_rt=$cookie")
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String? = json()["code"]?.jsonPrimitive?.content

    private suspend fun HttpResponse.metaField(): String? = json()["meta"]?.jsonObject?.get("field")?.jsonPrimitive?.content

    private fun code(phone: String) = stack.lastCodeSentTo(phone).value

    private fun wrong(code: String) = ((code.toInt() + 1) % 100_000).toString().padStart(5, '0')

    private fun phoneOf(userId: Long): String = transaction(db) { UserTbl.selectAll().where { UserTbl.id eq userId }.single()[UserTbl.phone]!! }

    private fun sessionsOf(userId: Long) = transaction(db) {
        AuthSessionTbl.selectAll().where { AuthSessionTbl.userId eq userId }.orderBy(AuthSessionTbl.id).toList()
    }

    private fun openCode(phone: String, purpose: OtpPurpose) = transaction(db) {
        OtpChallengeTbl.selectAll().where { (OtpChallengeTbl.phone eq phone) and (OtpChallengeTbl.purpose eq purpose) }
            .orderBy(OtpChallengeTbl.id).last()
    }

    @Test
    fun `with both codes the account moves, every session ends, this device gets a new one, and the old phone is warned`() = stack.app {
        val old = phone(1)
        val new = phone(2)
        val laptop = login(old)
        clock.advance(Duration.ofSeconds(121))
        val here = login(old)

        val requested = request(here.accessToken, new)
        assertEquals(HttpStatusCode.OK, requested.status)
        assertEquals(
            """{"currentPhone":"$old","newPhone":"$new","codeLength":5,"expiresInSeconds":300,"resendAvailableInSeconds":120}""",
            requested.bodyAsText(),
        )

        val response = verify(here.accessToken, new, code(old), code(new))

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals(new, body["user"]?.jsonObject?.get("phone")?.jsonPrimitive?.content)
        assertEquals(new, JWT.decode(body["accessToken"]?.jsonPrimitive?.content).getClaim("phone").asString())
        val newCookie = response.headers[HttpHeaders.SetCookie].orEmpty().substringAfter("kiva_rt=").substringBefore(';')
        assertEquals(43, newCookie.length)

        assertEquals(new, phoneOf(here.userId))
        val sessions = sessionsOf(here.userId)
        assertEquals(listOf(RevokeReason.PHONE_CHANGED, RevokeReason.PHONE_CHANGED, null), sessions.map { it[AuthSessionTbl.revokeReason] })
        assertEquals(HttpStatusCode.Unauthorized, refresh(laptop.cookie).status)
        assertEquals(HttpStatusCode.OK, refresh(newCookie).status)

        assertEquals(listOf(IranianMobileNumber.constructFromPersisted(old) to new.takeLast(4)), stack.sms.notices)
        val event = transaction(db) {
            SecurityEventTbl.selectAll().where { (SecurityEventTbl.type eq "PHONE_CHANGED") and (SecurityEventTbl.userId eq here.userId) }.single()
        }
        assertEquals(IranianMobileNumber.constructFromPersisted(old).masked(), event[SecurityEventTbl.detail]["from"]?.jsonPrimitive?.content)
    }

    @Test
    fun `only the new phone's code right is 422 OTP_INVALID on currentPhoneCode, nothing changes, and the right code stays usable`() = stack.app {
        val old = phone(3)
        val new = phone(4)
        val me = login(old)
        request(me.accessToken, new)

        val response = verify(me.accessToken, new, wrong(code(old)), code(new))

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("OTP_INVALID", response.code())
        assertEquals("currentPhoneCode", response.metaField())
        assertEquals("4", response.json()["meta"]?.jsonObject?.get("attemptsLeft")?.jsonPrimitive?.content)
        assertEquals(old, phoneOf(me.userId))
        assertEquals(1.toShort(), openCode(old, OtpPurpose.PHONE_CHANGE_CURRENT)[OtpChallengeTbl.failedAttempts])
        val newCode = openCode(new, OtpPurpose.PHONE_CHANGE_NEW)
        assertNull(newCode[OtpChallengeTbl.consumedOn])
        assertEquals(0.toShort(), newCode[OtpChallengeTbl.failedAttempts])

        assertEquals(HttpStatusCode.OK, verify(me.accessToken, new, code(old), code(new)).status)
        assertEquals(new, phoneOf(me.userId))
    }

    @Test
    fun `a new phone of another account is not told by the request, only by both right codes, with 409`() = stack.app {
        val old = phone(5)
        val taken = phone(6)
        val me = login(old)
        val owner = login(taken)

        val requested = request(me.accessToken, taken)
        assertEquals(HttpStatusCode.OK, requested.status)
        assertEquals(
            """{"currentPhone":"$old","newPhone":"$taken","codeLength":5,"expiresInSeconds":300,"resendAvailableInSeconds":120}""",
            requested.bodyAsText(),
        )

        val response = verify(me.accessToken, taken, code(old), code(taken))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("PHONE_ALREADY_REGISTERED", response.code())
        assertEquals(old, phoneOf(me.userId))
        assertEquals(taken, phoneOf(owner.userId))
        // The codes were right and are used up: they can't be tried again.
        assertEquals("OTP_EXPIRED", verify(me.accessToken, taken, code(old), code(taken)).code())
    }

    @Test
    fun `the current phone as the new one is 400 PHONE_SAME_AS_CURRENT on newPhone`() = stack.app {
        val me = login(phone(7))

        val response = request(me.accessToken, "+98 990 000 0007")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.json()["errors"]?.jsonArray?.single()?.jsonObject
        assertEquals("newPhone" to "PHONE_SAME_AS_CURRENT", error?.get("field")?.jsonPrimitive?.content to error?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `the limits of both phones apply`() = stack.app {
        val me = login(phone(8))
        request(me.accessToken, phone(9))
        assertEquals("OTP_RESEND_TOO_SOON", request(me.accessToken, phone(9)).code())

        // phone(10) got five login codes this hour: no code can go to it now, so no change to it either.
        repeat(5) {
            clock.advance(Duration.ofSeconds(121))
            client.post("$API_V1/auth/otp/send") {
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"${phone(10)}"}""")
            }
        }
        val busy = request(me.accessToken, phone(10))
        assertEquals(HttpStatusCode.TooManyRequests, busy.status)
        assertEquals("RATE_LIMITED", busy.code())
    }

    @Test
    fun `after a change, logging in with the old phone makes a new account`() = stack.app {
        val old = phone(11)
        val me = login(old)
        request(me.accessToken, phone(12))
        verify(me.accessToken, phone(12), code(old), code(phone(12)))
        clock.advance(Duration.ofSeconds(121))

        val again = login(old)

        assertTrue(again.isNewUser)
        assertNotEquals(me.userId, again.userId)
    }

    @Test
    fun `the current phone's code moves the account only to the new phone it was sent for`() = stack.app {
        val old = phone(13)
        val me = login(old)
        request(me.accessToken, phone(14))

        val elsewhere = verify(me.accessToken, phone(15), code(old), code(phone(14)))

        assertEquals("OTP_EXPIRED", elsewhere.code())
        assertEquals("currentPhoneCode", elsewhere.metaField())
        assertEquals(old, phoneOf(me.userId))
    }

    @Test
    fun `an access token of a session that was logged out can't start a change`() = stack.app {
        val me = login(phone(16))
        client.post("$API_V1/auth/logout") {
            header(HttpHeaders.Origin, ORIGIN)
            header(HttpHeaders.Cookie, "kiva_rt=${me.cookie}")
        }

        val response = request(me.accessToken, phone(17))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(stack.sms.sent.none { it.first.value == phone(17) })
    }
}
