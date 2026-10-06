package com.kiva.common.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuantityTest {

    @Test
    fun `from 1 up to 10,000`() {
        assertEquals(1, Quantity.makeNew(1).getOrNull()?.value)
        assertEquals(10_000, Quantity.makeNew(10_000).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, 10_001])
    fun `zero, negative or too many is QUANTITY_INVALID on the quantity field`(raw: Int) {
        assertEquals(Result.failure(Quantity.Failure.Invalid), Quantity.makeNew(raw))
        assertEquals("quantity", Quantity.Failure.Invalid.field)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { Quantity.constructFromPersisted(0) }
    }
}
