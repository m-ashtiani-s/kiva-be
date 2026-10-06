package com.kiva.domain.aggregate.color.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ColorNameTest {

    @Test
    fun `spaces around the name are trimmed, the text itself is kept as typed`() {
        assertEquals("یاسی", ColorName.makeNew("  یاسی  ").getOrNull()?.value)
        assertEquals("سبز ۲", ColorName.makeNew("سبز ۲").getOrNull()?.value) // Persian digits stay: names are shown as they are
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t"])
    fun `an empty name is COLOR_NAME_REQUIRED on the name field`(raw: String) {
        assertEquals(Result.failure(ColorName.Failure.Blank), ColorName.makeNew(raw))
        assertEquals("COLOR_NAME_REQUIRED", ColorName.Failure.Blank.code)
        assertEquals("name", ColorName.Failure.Blank.field)
    }

    @Test
    fun `at most 50 characters, and the failure tells the client the limit`() {
        assertEquals(50, ColorName.makeNew("ی".repeat(50)).getOrNull()?.value?.length)
        assertEquals(Result.failure(ColorName.Failure.TooLong), ColorName.makeNew("ی".repeat(51)))
        assertEquals(mapOf<String, Any>("maxLength" to 50), ColorName.Failure.TooLong.meta)
    }

    @Test
    fun `constructFromPersisted keeps the stored value`() {
        assertEquals(ColorName.makeNew("یاسی").getOrNull(), ColorName.constructFromPersisted("یاسی"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `a broken stored value is a bug, so constructFromPersisted throws instead of building it`(stored: String) {
        assertFailsWith<IllegalArgumentException> { ColorName.constructFromPersisted(stored) }
    }
}
