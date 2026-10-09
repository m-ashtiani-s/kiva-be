package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.IssuedSession
import com.kiva.application.dto.UserDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The contract's AuthResponse: AuthTokens plus the user. The refresh token is not here; it goes in the HttpOnly
 * cookie, where the page's JavaScript can't read it.
 *
 * @property expiresIn the access token's lifetime, in seconds.
 * @property refreshExpiresIn the cookie's lifetime, in seconds: the same as its Max-Age.
 * @property cart the cart after merging a guest's: always null until the cart phase.
 */
@Serializable
data class AuthHttpResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val refreshExpiresIn: Long,
    val isNewUser: Boolean,
    val user: UserHttpResponse,
    val cart: JsonObject?,
) {
    // The access token is a secret, if this is ever printed.
    override fun toString(): String = "AuthHttpResponse(user=${user.id}, isNewUser=$isNewUser, accessToken=<hidden>)"

    companion object {
        fun of(session: IssuedSession, user: UserDto) = AuthHttpResponse(
            accessToken = session.accessToken,
            tokenType = "Bearer",
            expiresIn = session.accessTokenExpiresIn.seconds,
            refreshExpiresIn = session.refreshTokenMaxAge.seconds,
            isNewUser = session.isNewUser,
            user = UserHttpResponse.from(user),
            cart = null,
        )
    }
}
