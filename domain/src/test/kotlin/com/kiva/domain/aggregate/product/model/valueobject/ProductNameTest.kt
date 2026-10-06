package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProductNameTest {

    @Test
    fun `spaces around the name are trimmed, the text itself is kept as typed`() {
        assertEquals("کیف دوشی ماهک", ProductName.makeNew("  کیف دوشی ماهک  ").getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `an empty name is PRODUCT_NAME_REQUIRED on the name field`(raw: String) {
        assertEquals(Result.failure(ProductName.Failure.Blank), ProductName.makeNew(raw))
        assertEquals("PRODUCT_NAME_REQUIRED", ProductName.Failure.Blank.code)
        assertEquals("name", ProductName.Failure.Blank.field)
    }

    @Test
    fun `at most 120 characters, and the failure tells the client the limit`() {
        assertEquals(120, ProductName.makeNew("ک".repeat(120)).getOrNull()?.value?.length)
        assertEquals(Result.failure(ProductName.Failure.TooLong), ProductName.makeNew("ک".repeat(121)))
        assertEquals(mapOf<String, Any>("maxLength" to 120), ProductName.Failure.TooLong.meta)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { ProductName.constructFromPersisted("  ") }
    }
}
