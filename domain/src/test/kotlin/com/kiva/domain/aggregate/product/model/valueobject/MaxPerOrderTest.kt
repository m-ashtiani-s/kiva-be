package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MaxPerOrderTest {

    @Test
    fun `at least 1, and 5 when not given (contract default)`() {
        assertEquals(1, MaxPerOrder.makeNew(1).getOrNull()?.value)
        assertEquals(5, MaxPerOrder.DEFAULT.value)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `zero or negative is MAX_PER_ORDER_INVALID on the maxPerOrder field`(raw: Int) {
        assertEquals(Result.failure(MaxPerOrder.Failure.Invalid), MaxPerOrder.makeNew(raw))
        assertEquals("maxPerOrder", MaxPerOrder.Failure.Invalid.field)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { MaxPerOrder.constructFromPersisted(0) }
    }
}
