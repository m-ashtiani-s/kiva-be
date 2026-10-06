package com.kiva.domain.aggregate.inventory.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StockDeltaTest {

    @ParameterizedTest
    @ValueSource(ints = [1, -1, 10_000, -10_000])
    fun `any change up to 10,000 either way`(raw: Int) {
        assertEquals(raw, StockDelta.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 10_001, -10_001, Int.MIN_VALUE])
    fun `zero or a typo-sized number is STOCK_DELTA_INVALID on the delta field`(raw: Int) {
        assertEquals(Result.failure(StockDelta.Failure.Invalid), StockDelta.makeNew(raw))
        assertEquals("delta", StockDelta.Failure.Invalid.field)
    }

    @Test
    fun `positive is an increase, negative is not`() {
        assertEquals(listOf(true, false), listOf(StockDelta.makeNew(3), StockDelta.makeNew(-3)).map { it.getOrNull()!!.isIncrease })
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { StockDelta.constructFromPersisted(0) }
    }
}
