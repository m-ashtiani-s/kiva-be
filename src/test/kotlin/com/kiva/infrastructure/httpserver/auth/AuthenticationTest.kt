package com.kiva.infrastructure.httpserver.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.httpserver.configureHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals

/**
 * The access token check on a route that needs a login and on one where a guest is fine. No database: the token alone
 * decides here (the panel's database check is RequireRolesOnDatabaseTest).
 */
class AuthenticationTest {

    private val secret = ByteArray(32) { 8 }
    private val clock = MutableClock(Instant.parse("2026-10-09T08:30:00Z"))
    private val tokens = JwtAccessTokens(secret, clock)
    private val user = User.register(UserId.constructFromPersisted(1024), IranianMobileNumber.constructFromPersisted("09121234567"), clock.now)
    private val session = AuthSession.start(SessionId.constructFromPersisted(88), user.id, user.roles, clock.now, SessionDevice.UNKNOWN)

    private fun token(): String = tokens.issue(user, session, clock.now).value

    /** A token signed with [key], with the claims [build] gives it on top of a valid set. */
    private fun crafted(key: ByteArray = secret, audience: String = "kiva", build: com.auth0.jwt.JWTCreator.Builder.() -> Unit = {}): String =
        JWT.create().withIssuer("kiva-api").withAudience(audience).withSubject("1024").withClaim("sid", "88")
            .withClaim("roles", listOf("CUSTOMER")).withIssuedAt(clock.now).withExpiresAt(clock.now.plusSeconds(900))
            .apply(build)
            .sign(Algorithm.HMAC256(key))

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            configureAuthentication(tokens)
            routing {
                authenticate(ACCESS) {
                    get("/me") { call.respondText("user ${call.accessPrincipal().userId.value}") }
                }
                authenticate(ACCESS, optional = true) {
                    get("/cart") { call.respondText(call.principal<AccessPrincipal>()?.let { "user ${it.userId.value}" } ?: "guest") }
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(path: String, authorization: String?): HttpResponse =
        client.get(path) { if (authorization != null) header(HttpHeaders.Authorization, authorization) }

    private suspend fun HttpResponse.code(): String? = Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.assertRefusedAs(code: String, authorization: String?, path: String = "/me") {
        val response = call(path, authorization)
        assertEquals(HttpStatusCode.Unauthorized, response.status, "for $authorization")
        assertEquals(code, response.code(), "for $authorization")
        assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
    }

    @Nested
    inner class LoginNeeded {
        @Test
        fun `a valid token lets the caller in, as the token's user`() = withApp {
            val response = call("/me", "Bearer ${token()}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("user 1024", response.bodyAsText())
        }

        @Test
        fun `no token is 401 UNAUTHORIZED`() = withApp {
            assertRefusedAs("UNAUTHORIZED", authorization = null)
        }

        @Test
        fun `a token past its 15 minutes is 401 TOKEN_EXPIRED, so the client refreshes`() = withApp {
            val token = token()
            clock.advance(Duration.ofMinutes(16))

            assertRefusedAs("TOKEN_EXPIRED", "Bearer $token")
        }

        @Test
        fun `a few seconds of clock difference are forgiven`() = withApp {
            val token = token()
            clock.advance(Duration.ofMinutes(15).plusSeconds(3))

            assertEquals(HttpStatusCode.OK, call("/me", "Bearer $token").status)
        }

        @Test
        fun `a changed signature, alg none, another key or another audience is 401 UNAUTHORIZED`() = withApp {
            val (header, payload, signature) = token().split('.')
            val none = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
            val flipped = (if (signature.first() == 'A') "B" else "A") + signature.drop(1)

            assertRefusedAs("UNAUTHORIZED", "Bearer $header.$payload.$flipped")
            assertRefusedAs("UNAUTHORIZED", "Bearer $none.$payload.")
            assertRefusedAs("UNAUTHORIZED", "Bearer ${crafted(key = ByteArray(32) { 9 })}")
            assertRefusedAs("UNAUTHORIZED", "Bearer ${crafted(audience = "someone-else")}")
        }

        @Test
        fun `a forged token that has also expired is UNAUTHORIZED, never TOKEN_EXPIRED`() = withApp {
            val forged = crafted(key = ByteArray(32) { 9 })
            clock.advance(Duration.ofHours(1))

            assertRefusedAs("UNAUTHORIZED", "Bearer $forged")
        }

        @Test
        fun `a well-signed token with claims this server never writes is 401 UNAUTHORIZED`() = withApp {
            assertRefusedAs("UNAUTHORIZED", "Bearer ${crafted { withSubject("0") }}")
            assertRefusedAs("UNAUTHORIZED", "Bearer ${crafted { withClaim("sid", null as String?) }}")
            assertRefusedAs("UNAUTHORIZED", "Bearer ${crafted { withClaim("roles", listOf("CUSTOMER", "OWNER")) }}")
            assertRefusedAs("UNAUTHORIZED", "Bearer ${crafted { withClaim("roles", listOf("ADMIN")) }}")
        }

        @Test
        fun `another scheme or a broken header is 401 UNAUTHORIZED`() = withApp {
            assertRefusedAs("UNAUTHORIZED", "Basic a2l2YTpraXZh")
            assertRefusedAs("UNAUTHORIZED", "Bearer")
            assertRefusedAs("UNAUTHORIZED", "Bearer not.a.jwt")
        }
    }

    @Nested
    inner class GuestAllowed {
        @Test
        fun `no token is a guest`() = withApp {
            val response = call("/cart", authorization = null)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("guest", response.bodyAsText())
        }

        @Test
        fun `a valid token is its user`() = withApp {
            assertEquals("user 1024", call("/cart", "Bearer ${token()}").bodyAsText())
        }

        /** Not a silent guest: the client must refresh, or a logged-in customer would see an empty cart. */
        @Test
        fun `an expired token is 401 TOKEN_EXPIRED, not a guest`() = withApp {
            val token = token()
            clock.advance(Duration.ofMinutes(16))

            assertRefusedAs("TOKEN_EXPIRED", "Bearer $token", path = "/cart")
        }

        @Test
        fun `a forged token is 401 UNAUTHORIZED, not a guest`() = withApp {
            assertRefusedAs("UNAUTHORIZED", "Bearer ${crafted(key = ByteArray(32) { 9 })}", path = "/cart")
        }
    }
}
