package com.kiva.infrastructure.cart

import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RandomCartIdsTest {

    private val ids = RandomCartIds()

    @Test
    fun `a guest token is gct_ and 43 base64url characters, 32 random bytes, and its hash is 32 bytes`() {
        val token = ids.nextGuestToken()
        val secret = token.reveal().removePrefix("gct_")

        assertTrue(token.reveal().startsWith("gct_"))
        assertEquals(43, secret.length)
        assertTrue(secret.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(32, Base64.getUrlDecoder().decode(secret).size)
        assertEquals(TokenHash.LENGTH, token.hash().toByteArray().size)
        assertFalse("$token".contains(secret))
    }

    @Test
    fun `ids are cart_ or ci_ and 10 letters or digits`() {
        assertTrue(Regex("cart_[0-9A-Za-z]{10}").matches(ids.nextCartPublicId().value))
        assertTrue(Regex("ci_[0-9A-Za-z]{10}").matches(ids.nextCartItemId().value))
    }

    @Test
    fun `nothing repeats in a thousand of each`() {
        assertEquals(1000, List(1000) { ids.nextGuestToken().reveal() }.toSet().size)
        assertEquals(1000, List(1000) { ids.nextCartPublicId() }.toSet().size)
        assertEquals(1000, List(1000) { ids.nextCartItemId() }.toSet().size)
    }
}
