package com.kiva.infrastructure.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.user.entity.User
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Signing and checking access tokens. Issued at the real time, since the verifier checks expiry against the real clock. */
class JwtAccessTokensTest {

    private val secret = ByteArray(32) { 8 }
    private val tokens = JwtAccessTokens(secret)
    private val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val user = User.register(UserId.constructFromPersisted(1024), IranianMobileNumber.constructFromPersisted("09121234567"), now)
    private val session = AuthSession.start(SessionId.constructFromPersisted(88), user.id, user.roles, now, SessionDevice.UNKNOWN)

    private fun token(): String = tokens.issue(user, session, now).value

    @Test
    fun `a token carries the user, the session, the roles and the phone, for 15 minutes`() {
        val issued = tokens.issue(user, session, now)
        val jwt = tokens.verifier.verify(issued.value)

        assertEquals("1024", jwt.subject)
        assertEquals("88", jwt.getClaim("sid").asString())
        assertEquals(listOf("CUSTOMER"), jwt.getClaim("roles").asList(String::class.java))
        assertEquals("09121234567", jwt.getClaim("phone").asString())
        assertEquals("kiva-api", jwt.issuer)
        assertEquals(listOf("kiva"), jwt.audience)
        assertEquals(now, jwt.issuedAtAsInstant)
        assertEquals(now.plusSeconds(900), jwt.expiresAtAsInstant)
        assertEquals(Duration.ofMinutes(15), issued.expiresIn)
    }

    @Test
    fun `it is an HS256 JWT`() {
        val jwt = JWT.decode(token())

        assertEquals("HS256", jwt.algorithm)
        assertEquals("JWT", jwt.type)
    }

    @Test
    fun `a token signed with another key is refused`() {
        assertFailsWith<JWTVerificationException> { JwtAccessTokens(ByteArray(32) { 9 }).verifier.verify(token()) }
    }

    @Test
    fun `a token that says alg none is refused, whatever it claims`() {
        val (_, payload, _) = token().split('.')
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())

        assertFailsWith<JWTVerificationException> { tokens.verifier.verify("$header.$payload.") }
    }

    @Test
    fun `a token whose roles were changed is refused`() {
        val (header, _, signature) = token().split('.')
        val forged = JWT.create().withIssuer("kiva-api").withAudience("kiva").withSubject("1024").withClaim("sid", "88")
            .withClaim("roles", listOf("CUSTOMER", "ADMIN")).withIssuedAt(now).withExpiresAt(now.plusSeconds(900))
            .sign(Algorithm.HMAC256(ByteArray(32) { 9 }))
        val forgedPayload = forged.split('.')[1]

        assertFailsWith<JWTVerificationException> { tokens.verifier.verify("$header.$forgedPayload.$signature") }
    }

    @Test
    fun `an expired token is refused`() {
        val longAgo = now.minus(Duration.ofMinutes(20))
        val old = tokens.issue(user, AuthSession.start(SessionId.constructFromPersisted(88), user.id, user.roles, longAgo, SessionDevice.UNKNOWN), longAgo)

        assertFailsWith<JWTVerificationException> { tokens.verifier.verify(old.value) }
    }

    @Test
    fun `a secret shorter than 32 bytes is refused`() {
        assertFailsWith<IllegalArgumentException> { JwtAccessTokens(ByteArray(31)) }
    }

    @Test
    fun `an issued token never shows in a string`() {
        val issued = tokens.issue(user, session, now)

        assertEquals("IssuedAccessToken(value=<hidden>, expiresIn=PT15M)", issued.toString())
    }
}
