package com.kiva.application.port

import com.kiva.domain.aggregate.session.model.valueobject.TokenHash

/**
 * Makes refresh tokens and their hashes. Only the hash is ever stored; the token goes to the browser in an HttpOnly
 * cookie and nowhere else.
 */
interface SessionTokens {

    /** A new token from a secure random source, and the hash to store. */
    fun newRefreshToken(): NewRefreshToken

    /** The hash of a token a browser sent back: what to look it up by. */
    fun hashOf(token: String): TokenHash

    /** @property token the secret itself, for the cookie only; [toString] hides it. */
    class NewRefreshToken(val token: String, val hash: TokenHash) {
        override fun toString(): String = "NewRefreshToken(token=<hidden>, hash=$hash)"
    }
}
