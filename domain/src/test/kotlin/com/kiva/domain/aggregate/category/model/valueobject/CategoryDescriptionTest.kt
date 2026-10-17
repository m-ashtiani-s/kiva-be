package com.kiva.domain.aggregate.category.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CategoryDescriptionTest {

    @Test
    fun `trimmed and kept as typed - null or blank is no description`() {
        assertEquals("کیف‌های دوشی روزمره", CategoryDescription.makeNew("  کیف‌های دوشی روزمره ").getOrNull()?.value)
        assertEquals(Result.success(null), CategoryDescription.makeNew(null))
        assertEquals(Result.success(null), CategoryDescription.makeNew("   "))
    }

    @Test
    fun `at most 500 characters - 501 is CATEGORY_DESCRIPTION_TOO_LONG on description with the limit`() {
        assertEquals(500, CategoryDescription.makeNew("ک".repeat(500)).getOrNull()?.value?.length)

        val failure = CategoryDescription.makeNew("ک".repeat(501)).failureOrNull()

        assertEquals("CATEGORY_DESCRIPTION_TOO_LONG", failure?.code)
        assertEquals("description", failure?.field)
        assertEquals(mapOf<String, Any>("maxLength" to 500), failure?.meta)
    }

    @Test
    fun `a broken stored value is a bug`() {
        assertFailsWith<IllegalArgumentException> { CategoryDescription.constructFromPersisted(" ") }
    }
}
