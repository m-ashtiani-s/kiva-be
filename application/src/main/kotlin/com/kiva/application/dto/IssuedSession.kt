package com.kiva.application.dto

import com.kiva.common.valueobject.UserId
import java.time.Duration

/**
 * What a login (and later a refresh or a phone change) hands out: the one exception to "a command returns only an id".
 * These are the ids of what was made, and they exist only at this moment: the refresh token is stored as a hash, so no
 * query can ever read it back. The user's profile is not here; the route reads it with GetMeUc.
 *
 * @property refreshToken the secret for the HttpOnly cookie, never for a body or a log.
 * @property refreshTokenMaxAge how long the cookie should live: until the session's idle end.
 */
class IssuedSession(
    val userId: UserId,
    val isNewUser: Boolean,
    val accessToken: String,
    val accessTokenExpiresIn: Duration,
    val refreshToken: String,
    val refreshTokenMaxAge: Duration,
) {
    // Both tokens are secrets.
    override fun toString(): String = "IssuedSession(userId=${userId.value}, isNewUser=$isNewUser, tokens=<hidden>)"
}
