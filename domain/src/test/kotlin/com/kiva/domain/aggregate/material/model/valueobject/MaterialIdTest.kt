package com.kiva.domain.aggregate.material.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MaterialIdTest {

    @ParameterizedTest
    @ValueSource(longs = [1, 12, Long.MAX_VALUE])
    fun `any positive number is accepted as it is`(raw: Long) {
        assertEquals(raw, MaterialId.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, Long.MIN_VALUE])
    fun `zero or negative is MATERIAL_ID_INVALID`(raw: Long) {
        assertEquals(Result.failure(MaterialId.Failure.Invalid), MaterialId.makeNew(raw))
    }

    @Test
    fun `the failure is an input problem without a field, the caller names the field`() {
        assertEquals("MATERIAL_ID_INVALID", MaterialId.Failure.Invalid.code)
        assertEquals(FailureKind.INVALID, MaterialId.Failure.Invalid.kind)
        assertNull(MaterialId.Failure.Invalid.field)
    }

    @Test
    fun `constructFromPersisted keeps the stored value and compares by value`() {
        assertEquals(MaterialId.makeNew(4).getOrNull(), MaterialId.constructFromPersisted(4))
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `a broken stored value is a bug, so constructFromPersisted throws instead of building it`(stored: Long) {
        assertFailsWith<IllegalArgumentException> { MaterialId.constructFromPersisted(stored) }
    }
}
