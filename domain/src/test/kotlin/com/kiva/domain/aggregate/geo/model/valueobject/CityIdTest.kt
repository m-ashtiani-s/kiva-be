package com.kiva.domain.aggregate.geo.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CityIdTest {

    @ParameterizedTest
    @ValueSource(ints = [1, 394, 1518, Int.MAX_VALUE])
    fun `any positive number is accepted as it is`(raw: Int) {
        assertEquals(raw, CityId.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, Int.MIN_VALUE])
    fun `zero or negative is CITY_ID_INVALID, without a field`(raw: Int) {
        assertEquals(Result.failure(CityId.Failure.Invalid), CityId.makeNew(raw))
        assertEquals("CITY_ID_INVALID", CityId.Failure.Invalid.code)
        assertNull(CityId.Failure.Invalid.field)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertEquals(CityId.makeNew(394).getOrNull(), CityId.constructFromPersisted(394))
        assertFailsWith<IllegalArgumentException> { CityId.constructFromPersisted(-5) }
    }
}
