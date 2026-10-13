package com.kiva.domain.aggregate.cart.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import java.security.MessageDigest

/**
 * The secret that reaches a guest's cart (the `X-Cart-Token` header): `gct_` and 32 random bytes in base64url without
 * padding (43 characters). Made by CartIdGenerator from a secure random source.
 *
 * Only its SHA-256 ([hash]) is stored, like a refresh token: with 256 random bits there is nothing to guess, so no
 * key or salt is needed, and a copy of the database doesn't open anybody's cart. It reaches only a guest cart, never a
 * customer's, and nothing personal is behind it.
 *
 * [toString] never shows it, so neither does a log line or a failed check.
 */
@JvmInline
value class CartToken private constructor(private val value: String) {

    // Second wall: [makeNew] already refuses a malformed token. The message leaves the value out on purpose.
    init {
        require(FORMAT.matches(value)) { "Invalid CartToken" }
    }

    /** What is stored and looked up: SHA-256 of the whole token, `gct_` included. */
    fun hash(): TokenHash = TokenHash(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)))

    /** The token itself, only for the response header that hands it to the browser. */
    fun reveal(): String = value

    override fun toString(): String = "CartToken(****)"

    companion object {
        const val PREFIX = "gct_"

        // 32 bytes in base64url without padding are exactly 43 characters. matches() checks the whole string.
        private val FORMAT = Regex("gct_[A-Za-z0-9_-]{43}")

        /**
         * A token from the header, or from the generator. A malformed one is not an error the shopper sees: the cart
         * routes treat it like a token nobody knows and start a new guest cart.
         */
        fun makeNew(raw: String): Result<CartToken, Failure> =
            if (FORMAT.matches(raw)) Result.success(CartToken(raw)) else Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("CART_TOKEN_INVALID")
    }
}
