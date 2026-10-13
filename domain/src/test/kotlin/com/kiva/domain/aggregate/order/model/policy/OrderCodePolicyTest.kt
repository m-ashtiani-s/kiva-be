package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderCodePolicyTest {

    private fun number(id: Long): Long = OrderCodePolicy.codeOf(OrderId.constructFromPersisted(id)).value.removePrefix("KV-").toLong()

    @Test
    fun `the first order is the contract's KV-218340`() {
        assertEquals("KV-218340", OrderCodePolicy.codeOf(OrderId.constructFromPersisted(1)).value)
    }

    @Test
    fun `900,000 ids in a row get 900,000 different six-digit codes, and no two neighbours are next to each other`() {
        val seen = BooleanArray(900_000)
        var previous = -1L
        for (id in 1L..900_000L) {
            val code = number(id)
            assertTrue(code in 100_000..999_999, "$id got $code")
            val slot = (code - 100_000).toInt()
            assertTrue(!seen[slot], "$id got $code twice")
            seen[slot] = true
            if (previous >= 0) assertTrue(abs(code - previous) > 1, "$id got $code right after $previous")
            previous = code
        }
        assertTrue(seen.all { it })
    }

    @Test
    fun `after 900,000 orders the codes go on with seven digits, then eight, still each its own`() {
        val sevenDigits = (900_001L..900_500L).map(::number)
        val eightDigits = (9_900_001L..9_900_500L).map(::number)

        assertTrue(sevenDigits.all { it in 1_000_000..9_999_999 })
        assertTrue(eightDigits.all { it in 10_000_000..99_999_999 })
        assertEquals(500, sevenDigits.toSet().size)
        assertEquals(500, eightDigits.toSet().size)
    }

    @Test
    fun `every code is a valid OrderCode`() {
        listOf(1L, 2L, 899_999L, 900_000L, 900_001L, 9_900_000L, 99_900_000L).forEach {
            assertTrue(OrderCode.makeNew(OrderCodePolicy.codeOf(OrderId.constructFromPersisted(it)).value).isSuccess)
        }
    }
}
