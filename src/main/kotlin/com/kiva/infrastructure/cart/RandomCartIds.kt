package com.kiva.infrastructure.cart

import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.cart.model.valueobject.CartToken
import com.kiva.domain.aggregate.cart.repository.CartIdGenerator
import java.security.SecureRandom
import java.util.Base64

/**
 * The random parts of carts, from [SecureRandom]:
 * - public ids: 10 letters and digits after `cart_` or `ci_` (about 59 bits): nothing to count, and the database's
 *   unique keys stand behind a repeat;
 * - guest tokens: `gct_` and 32 random bytes in base64url without padding (43 characters). Only their hash is stored.
 */
class RandomCartIds(private val random: SecureRandom = SecureRandom()) : CartIdGenerator {

    override fun nextCartPublicId(): CartPublicId = CartPublicId.constructFromPersisted("cart_" + randomText())

    override fun nextCartItemId(): CartItemId = CartItemId.constructFromPersisted("ci_" + randomText())

    override fun nextGuestToken(): CartToken {
        val token = CartToken.PREFIX + ENCODER.encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        return CartToken.makeNew(token).getOrNull() ?: error("A generated cart token has the wrong form")
    }

    private fun randomText(): String = buildString(ID_LENGTH) { repeat(ID_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    companion object {
        const val TOKEN_BYTES = 32
        const val ID_LENGTH = 10
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    }
}
