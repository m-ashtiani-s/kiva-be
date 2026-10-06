package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProductIdTest {

    @ParameterizedTest
    @ValueSource(longs = [1, 16, Long.MAX_VALUE])
    fun `any positive number is accepted as it is`(raw: Long) {
        assertEquals(raw, ProductId.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `zero or negative is PRODUCT_ID_INVALID, without a field`(raw: Long) {
        assertEquals(Result.failure(ProductId.Failure.Invalid), ProductId.makeNew(raw))
        assertEquals("PRODUCT_ID_INVALID", ProductId.Failure.Invalid.code)
        assertNull(ProductId.Failure.Invalid.field)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertEquals(ProductId.makeNew(3).getOrNull(), ProductId.constructFromPersisted(3))
        assertFailsWith<IllegalArgumentException> { ProductId.constructFromPersisted(0) }
    }
}
