package com.kiva.infrastructure.auth

import com.kiva.application.port.SessionTokens
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Refresh tokens of 32 random bytes from [SecureRandom], written as base64url without padding (43 characters, safe in
 * a cookie as they are). Stored as their SHA-256: with 256 random bits there is nothing to try, so no key or salt is
 * needed.
 */
class RandomSessionTokens(private val random: SecureRandom = SecureRandom()) : SessionTokens {

    override fun newRefreshToken(): SessionTokens.NewRefreshToken {
        val token = ENCODER.encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        return SessionTokens.NewRefreshToken(token, hashOf(token))
    }

    override fun hashOf(token: String): TokenHash =
        TokenHash(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)))

    companion object {
        const val TOKEN_BYTES = 32
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    }
}
