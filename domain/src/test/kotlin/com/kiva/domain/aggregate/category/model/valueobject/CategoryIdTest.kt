package com.kiva.domain.aggregate.category.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CategoryIdTest {

    @ParameterizedTest
    @ValueSource(longs = [1, 8, Long.MAX_VALUE])
    fun `any positive number is accepted as it is`(raw: Long) {
        assertEquals(raw, CategoryId.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, Long.MIN_VALUE])
    fun `zero or negative is CATEGORY_ID_INVALID`(raw: Long) {
        assertEquals(Result.failure(CategoryId.Failure.Invalid), CategoryId.makeNew(raw))
    }

    @Test
    fun `the failure is an input problem without a field, the caller names the field`() {
        assertEquals("CATEGORY_ID_INVALID", CategoryId.Failure.Invalid.code)
        assertEquals(FailureKind.INVALID, CategoryId.Failure.Invalid.kind)
        assertNull(CategoryId.Failure.Invalid.field)
    }

    @Test
    fun `constructFromPersisted keeps the stored value and compares by value`() {
        assertEquals(CategoryId.makeNew(3).getOrNull(), CategoryId.constructFromPersisted(3))
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `a broken stored value is a bug, so constructFromPersisted throws instead of building it`(stored: Long) {
        assertFailsWith<IllegalArgumentException> { CategoryId.constructFromPersisted(stored) }
    }
}
