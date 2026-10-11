package com.kiva.application.port

import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.user.entity.User
import java.time.Duration
import java.time.Instant

/** Signs the short-lived access tokens a client sends as `Authorization: Bearer`. */
interface AccessTokens {

    /**
     * A token for [user] in [session], from [now] for the access token lifetime. It carries the user's id, the
     * session's id and the user's roles and phone as they are now.
     */
    fun issue(user: User, session: AuthSession, now: Instant): IssuedAccessToken

    /** @property value the signed token; a secret, so [toString] hides it. */
    class IssuedAccessToken(val value: String, val expiresIn: Duration) {
        override fun toString(): String = "IssuedAccessToken(value=<hidden>, expiresIn=$expiresIn)"
    }
}
