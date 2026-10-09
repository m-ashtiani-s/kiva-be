package com.kiva.infrastructure.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.kiva.application.port.AccessTokens
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.policy.SessionPolicy
import com.kiva.domain.aggregate.user.entity.User
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Access tokens as JWTs signed with HMAC-SHA256 under [secret] (`kiva.auth.jwtSecret`), built and read only with the
 * java-jwt library: no JWT is put together or parsed by hand.
 *
 * Payload: `iss` kiva-api, `aud` kiva, `sub` the user's id, `sid` the session's id, `roles`, `phone`, `iat`, and
 * `exp` = `iat` + 15 minutes, in whole seconds.
 *
 * [verifier] has the algorithm fixed: a token that says `alg: none`, or one signed with any other key or algorithm, is
 * refused whatever its header claims. Changing the secret ends every access token at once; clients refresh silently,
 * since refresh tokens don't depend on it.
 *
 * @param secret at least 32 random bytes.
 */
class JwtAccessTokens(secret: ByteArray) : AccessTokens {

    init {
        require(secret.size >= MIN_SECRET_BYTES) { "The JWT secret must be at least $MIN_SECRET_BYTES bytes, was ${secret.size}" }
    }

    private val algorithm = Algorithm.HMAC256(secret.copyOf())

    /** Checks the signature with this algorithm and key only, then issuer, audience and time (5 seconds of leeway for clocks). */
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).acceptLeeway(LEEWAY_SECONDS).build()

    override fun issue(user: User, session: AuthSession, now: Instant): AccessTokens.IssuedAccessToken {
        val issuedAt = now.truncatedTo(ChronoUnit.SECONDS)
        val token = JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(user.id.value.toString())
            .withClaim(SESSION_CLAIM, session.id.value.toString())
            .withClaim(ROLES_CLAIM, user.roles.values.map { it.name })
            .withClaim(PHONE_CLAIM, user.phone.value)
            .withIssuedAt(issuedAt)
            .withExpiresAt(issuedAt.plus(SessionPolicy.ACCESS_TOKEN_LIFETIME))
            .sign(algorithm)
        return AccessTokens.IssuedAccessToken(token, SessionPolicy.ACCESS_TOKEN_LIFETIME)
    }

    companion object {
        const val MIN_SECRET_BYTES = 32
        const val ISSUER = "kiva-api"
        const val AUDIENCE = "kiva"
        const val SESSION_CLAIM = "sid"
        const val ROLES_CLAIM = "roles"
        const val PHONE_CLAIM = "phone"
        private const val LEEWAY_SECONDS = 5L
    }
}
