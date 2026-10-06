package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VariantIdTest {

    @ParameterizedTest
    @ValueSource(longs = [1, 101, Long.MAX_VALUE])
    fun `any positive number is accepted as it is`(raw: Long) {
        assertEquals(raw, VariantId.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `zero or negative is VARIANT_ID_INVALID, without a field`(raw: Long) {
        assertEquals(Result.failure(VariantId.Failure.Invalid), VariantId.makeNew(raw))
        assertEquals("VARIANT_ID_INVALID", VariantId.Failure.Invalid.code)
        assertNull(VariantId.Failure.Invalid.field)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertEquals(VariantId.makeNew(101).getOrNull(), VariantId.constructFromPersisted(101))
        assertFailsWith<IllegalArgumentException> { VariantId.constructFromPersisted(-1) }
    }
}
