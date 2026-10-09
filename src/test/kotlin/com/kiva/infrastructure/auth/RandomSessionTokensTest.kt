package com.kiva.infrastructure.auth

import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RandomSessionTokensTest {

    private val tokens = RandomSessionTokens()

    @Test
    fun `a refresh token is 32 random bytes in base64url, 43 characters a cookie takes as they are`() {
        val token = tokens.newRefreshToken().token

        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(token), token.length.toString())
    }

    @Test
    fun `every token is new`() {
        assertEquals(1_000, List(1_000) { tokens.newRefreshToken().token }.toSet().size)
    }

    @Test
    fun `what is stored is the token's SHA-256, and a token sent back hashes to it`() {
        val new = tokens.newRefreshToken()

        assertEquals(TokenHash(MessageDigest.getInstance("SHA-256").digest(new.token.toByteArray())), new.hash)
        assertEquals(new.hash, tokens.hashOf(new.token))
        val oneCharacterOff = new.token.dropLast(1) + if (new.token.last() == 'A') "B" else "A"
        assertNotEquals(new.hash, tokens.hashOf(oneCharacterOff))
    }

    @Test
    fun `a new token never shows in a string`() {
        val new = tokens.newRefreshToken()

        assertFalse(new.token in new.toString())
    }
}
