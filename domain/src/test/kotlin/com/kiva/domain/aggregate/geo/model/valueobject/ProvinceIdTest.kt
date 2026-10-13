package com.kiva.domain.aggregate.geo.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProvinceIdTest {

    @ParameterizedTest
    @ValueSource(ints = [1, 8, 31, Int.MAX_VALUE])
    fun `any positive number is accepted as it is`(raw: Int) {
        assertEquals(raw, ProvinceId.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, Int.MIN_VALUE])
    fun `zero or negative is PROVINCE_ID_INVALID, without a field`(raw: Int) {
        assertEquals(Result.failure(ProvinceId.Failure.Invalid), ProvinceId.makeNew(raw))
        assertEquals("PROVINCE_ID_INVALID", ProvinceId.Failure.Invalid.code)
        assertNull(ProvinceId.Failure.Invalid.field)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertEquals(ProvinceId.makeNew(8).getOrNull(), ProvinceId.constructFromPersisted(8))
        assertFailsWith<IllegalArgumentException> { ProvinceId.constructFromPersisted(0) }
    }
}
