package com.kiva.domain.aggregate.cart.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.CartFixtures.TOKEN
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CartValueObjectsTest {

    @Nested
    inner class Quantity {
        @Test
        fun `1 to 20 is a quantity`() {
            assertEquals(1, CartQuantity.makeNew(1).getOrNull()?.value)
            assertEquals(20, CartQuantity.makeNew(20).getOrNull()?.value)
        }

        @Test
        fun `0 and 21 are refused on the quantity field, with the limit for the message`() {
            listOf(0, 21, -1).forEach {
                val failure = CartQuantity.makeNew(it).failureOrNull()
                assertEquals("QUANTITY_INVALID", failure?.code)
                assertEquals(FailureKind.INVALID, failure?.kind)
                assertEquals("quantity", failure?.field)
                assertEquals(20, failure?.meta?.get("max"))
            }
        }

        @Test
        fun `a stored quantity outside 1 to 20 is a bug`() {
            assertFailsWith<IllegalArgumentException> { CartQuantity.constructFromPersisted(21) }
        }
    }

    @Nested
    inner class Ids {
        @Test
        fun `a line id is ci_ and 10 letters or digits`() {
            assertEquals("ci_8d2f1aB9xQ", CartItemId.makeNew("ci_8d2f1aB9xQ").getOrNull()?.value)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "ci_", "ci_8d2f1a", "ci_8d2f1aB9xQ1", "CI_8d2f1aB9xQ", "ci_8d2f1aB9x-", " ci_8d2f1aB9xQ"])
        fun `anything else is not a line id`(raw: String) {
            assertEquals("CART_ITEM_ID_INVALID", CartItemId.makeNew(raw).failureOrNull()?.code)
        }

        @Test
        fun `a cart id is cart_ and 10 letters or digits, never typed by anyone, so a wrong one is a bug`() {
            assertEquals("cart_3Fq8aZ09bC", CartPublicId.constructFromPersisted("cart_3Fq8aZ09bC").value)
            assertFailsWith<IllegalArgumentException> { CartPublicId.constructFromPersisted("cart_3Fq8") }
        }
    }

    @Nested
    inner class Token {
        private val token = CartToken.makeNew(TOKEN).getOrNull()!!

        @Test
        fun `a token is gct_ and 43 base64url characters, the 32 random bytes`() {
            assertEquals(TOKEN, token.reveal())
            assertEquals(43, TOKEN.removePrefix(CartToken.PREFIX).length)
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "",
                "gct_",
                "gct_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCd",
                "gct_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdEf",
                "gct_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789+/AbCdE",
                "xyz_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE",
                "gct_7f3a9c2e1b",
            ],
        )
        fun `a malformed token is refused, so the cart routes treat it as unknown`(raw: String) {
            assertEquals("CART_TOKEN_INVALID", CartToken.makeNew(raw).failureOrNull()?.code)
        }

        @Test
        fun `its hash is the 32-byte SHA-256 of the whole token`() {
            val expected = MessageDigest.getInstance("SHA-256").digest(TOKEN.toByteArray(Charsets.US_ASCII))

            assertContentEquals(expected, token.hash().toByteArray())
            assertEquals(TokenHash.LENGTH, token.hash().toByteArray().size)
            assertEquals(token.hash(), CartToken.makeNew(TOKEN).getOrNull()!!.hash())
        }

        @Test
        fun `printing it never shows it`() {
            assertFalse("$token".contains(TOKEN.removePrefix(CartToken.PREFIX)))
            assertFalse("${CartOwner.Guest(token.hash())}".contains(TOKEN))
        }
    }

    @Nested
    inner class Owner {
        @Test
        fun `two guests are the same owner exactly when their token hashes are`() {
            val token = CartToken.makeNew(TOKEN).getOrNull()!!
            val other = CartToken.makeNew(TOKEN.dropLast(1) + "F").getOrNull()!!

            assertEquals(CartOwner.Guest(token.hash()), CartOwner.Guest(token.hash()))
            assertNotEquals(CartOwner.Guest(token.hash()), CartOwner.Guest(other.hash()))
            assertTrue(CartOwner.User(UserId.constructFromPersisted(7)) == CartOwner.User(UserId.constructFromPersisted(7)))
        }
    }
}
