package com.kiva.common.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    private fun toman(amount: Long): Money = Money.ofToman(amount).getOrNull()!!

    @Nested
    inner class Creation {
        @Test
        fun `zero and positive amounts are accepted`() {
            assertEquals(0L, toman(0).toman)
            assertEquals(2_500_000L, toman(2_500_000).toman)
        }

        @Test
        fun `a negative amount is MONEY_NEGATIVE`() {
            assertEquals(Result.failure(Money.Failure.Negative), Money.ofToman(-1))
        }
    }

    @Nested
    inner class Arithmetic {
        @Test
        fun `adds two amounts`() {
            assertEquals(toman(350_000), toman(250_000) + toman(100_000))
        }

        @Test
        fun `multiplies by a quantity`() {
            assertEquals(toman(750_000), toman(250_000) * 3)
            assertEquals(Money.ZERO, toman(250_000) * 0)
        }

        @Test
        fun `sums a list, an empty list is zero`() {
            assertEquals(toman(600), listOf(toman(100), toman(200), toman(300)).sum())
            assertEquals(Money.ZERO, emptyList<Money>().sum())
        }

        @Test
        fun `converts to rials for the payment gateway`() {
            assertEquals(18_900_000L, toman(1_890_000).toRials())
        }

        @Test
        fun `a negative quantity is a bug and stops`() {
            assertFailsWith<IllegalArgumentException> { toman(100) * -1 }
        }

        @Test
        fun `overflow stops loudly instead of becoming a negative price`() {
            assertFailsWith<ArithmeticException> { toman(Long.MAX_VALUE) + toman(1) }
            assertFailsWith<ArithmeticException> { toman(Long.MAX_VALUE / 2 + 1) * 2 }
        }

        @Test
        fun `a plain Long would silently wrap around`() {
            // What Money protects us from:
            val plainLong = listOf(Long.MAX_VALUE).single()
            assertTrue(plainLong + 1 < 0)
        }
    }

    @Test
    fun `amounts compare by value`() {
        assertTrue(toman(100) < toman(200))
        assertEquals(toman(500), listOf(toman(300), toman(500), toman(100)).max())
    }
}
