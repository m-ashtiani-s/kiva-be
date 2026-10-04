package com.kiva.common.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    private fun rials(amount: Long): Money = Money.ofRials(amount).getOrNull()!!

    @Nested
    inner class Creation {
        @Test
        fun `zero and positive amounts are accepted`() {
            assertEquals(0L, rials(0).rials)
            assertEquals(2_500_000L, rials(2_500_000).rials)
        }

        @Test
        fun `a negative amount is MONEY_NEGATIVE`() {
            assertEquals(Result.failure(Money.Failure.Negative), Money.ofRials(-1))
        }
    }

    @Nested
    inner class Arithmetic {
        @Test
        fun `adds two amounts`() {
            assertEquals(rials(350_000), rials(250_000) + rials(100_000))
        }

        @Test
        fun `multiplies by a quantity`() {
            assertEquals(rials(750_000), rials(250_000) * 3)
            assertEquals(Money.ZERO, rials(250_000) * 0)
        }

        @Test
        fun `sums a list, an empty list is zero`() {
            assertEquals(rials(600), listOf(rials(100), rials(200), rials(300)).sum())
            assertEquals(Money.ZERO, emptyList<Money>().sum())
        }

        @Test
        fun `a negative quantity is a bug and stops`() {
            assertFailsWith<IllegalArgumentException> { rials(100) * -1 }
        }

        @Test
        fun `overflow stops loudly instead of becoming a negative price`() {
            assertFailsWith<ArithmeticException> { rials(Long.MAX_VALUE) + rials(1) }
            assertFailsWith<ArithmeticException> { rials(Long.MAX_VALUE / 2 + 1) * 2 }
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
        assertTrue(rials(100) < rials(200))
        assertEquals(rials(500), listOf(rials(300), rials(500), rials(100)).max())
    }
}
