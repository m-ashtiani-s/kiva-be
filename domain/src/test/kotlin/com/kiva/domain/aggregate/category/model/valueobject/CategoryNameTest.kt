package com.kiva.domain.aggregate.category.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CategoryNameTest {

    @Test
    fun `spaces around the name are trimmed, the text itself is kept as typed`() {
        assertEquals("کیف دوشی", CategoryName.makeNew("  کیف دوشی  ").getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `an empty name is CATEGORY_NAME_REQUIRED on the name field`(raw: String) {
        assertEquals(Result.failure(CategoryName.Failure.Blank), CategoryName.makeNew(raw))
        assertEquals("CATEGORY_NAME_REQUIRED", CategoryName.Failure.Blank.code)
        assertEquals("name", CategoryName.Failure.Blank.field)
    }

    @Test
    fun `at most 100 characters, and the failure tells the client the limit`() {
        assertEquals(100, CategoryName.makeNew("ک".repeat(100)).getOrNull()?.value?.length)
        assertEquals(Result.failure(CategoryName.Failure.TooLong), CategoryName.makeNew("ک".repeat(101)))
        assertEquals(mapOf<String, Any>("maxLength" to 100), CategoryName.Failure.TooLong.meta)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `a broken stored value is a bug, so constructFromPersisted throws instead of building it`(stored: String) {
        assertFailsWith<IllegalArgumentException> { CategoryName.constructFromPersisted(stored) }
    }
}
