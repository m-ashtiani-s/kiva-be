package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/** GET /me over kiva_test, with a token from a real login. */
class WebV1MeOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2034-03-01T09:00:00Z"))
    private val stack = AuthTestStack(clock)

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers()

    private suspend fun ApplicationTestBuilder.login(phone: String): JsonObject {
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val response = client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"${stack.lastCodeSentTo(phone).value}"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.me(token: String?): HttpResponse =
        client.get("$API_V1/me") { if (token != null) header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `the login's token reads the logged-in user's own account`() = stack.app {
        val login = login(phone(1))

        val response = me(login["accessToken"]?.jsonPrimitive?.content)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(login["user"], response.json())
    }

    @Test
    fun `without a token it is 401 UNAUTHORIZED, and with an expired one 401 TOKEN_EXPIRED`() = stack.app {
        val token = login(phone(2))["accessToken"]?.jsonPrimitive?.content
        assertEquals("UNAUTHORIZED", me(token = null).json()["code"]?.jsonPrimitive?.content)

        clock.advance(Duration.ofMinutes(16))
        assertEquals("TOKEN_EXPIRED", me(token).json()["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a valid token of an account that no longer exists is 401, so the client logs in again`() = stack.app {
        val gone = User.register(UserId.constructFromPersisted(Long.MAX_VALUE), IranianMobileNumber.constructFromPersisted(phone(3)), clock.now)
        val session = AuthSession.start(SessionId.constructFromPersisted(Long.MAX_VALUE), gone.id, gone.roles, clock.now, SessionDevice.UNKNOWN)

        val response = me(stack.accessTokens.issue(gone, session, clock.now).value)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("UNAUTHORIZED", response.json()["code"]?.jsonPrimitive?.content)
    }
}
