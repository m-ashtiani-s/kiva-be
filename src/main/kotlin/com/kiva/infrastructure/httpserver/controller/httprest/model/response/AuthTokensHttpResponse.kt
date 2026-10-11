package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.IssuedSession
import kotlinx.serialization.Serializable

/**
 * The contract's AuthTokens, the answer of a refresh. The refresh token is not here; when it changes, it comes in the
 * cookie.
 *
 * @property expiresIn the access token's lifetime, in seconds.
 * @property refreshExpiresIn how long the session's cookie still lives, in seconds.
 */
@Serializable
data class AuthTokensHttpResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val refreshExpiresIn: Long,
) {
    // The access token is a secret, if this is ever printed.
    override fun toString(): String = "AuthTokensHttpResponse(accessToken=<hidden>, expiresIn=$expiresIn)"

    companion object {
        fun of(session: IssuedSession) = AuthTokensHttpResponse(
            accessToken = session.accessToken,
            tokenType = "Bearer",
            expiresIn = session.accessTokenExpiresIn.seconds,
            refreshExpiresIn = session.refreshTokenMaxAge.seconds,
        )
    }
}
