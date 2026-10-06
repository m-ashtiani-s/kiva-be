package com.kiva.domain.aggregate.material.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MaterialNameTest {

    @Test
    fun `spaces around the name are trimmed, the text itself is kept as typed`() {
        assertEquals("چرم طبیعی گاوی", MaterialName.makeNew("  چرم طبیعی گاوی  ").getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `an empty name is MATERIAL_NAME_REQUIRED on the name field`(raw: String) {
        assertEquals(Result.failure(MaterialName.Failure.Blank), MaterialName.makeNew(raw))
        assertEquals("MATERIAL_NAME_REQUIRED", MaterialName.Failure.Blank.code)
        assertEquals("name", MaterialName.Failure.Blank.field)
    }

    @Test
    fun `at most 120 characters, and the failure tells the client the limit`() {
        assertEquals(120, MaterialName.makeNew("چ".repeat(120)).getOrNull()?.value?.length)
        assertEquals(Result.failure(MaterialName.Failure.TooLong), MaterialName.makeNew("چ".repeat(121)))
        assertEquals(mapOf<String, Any>("maxLength" to 120), MaterialName.Failure.TooLong.meta)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `a broken stored value is a bug, so constructFromPersisted throws instead of building it`(stored: String) {
        assertFailsWith<IllegalArgumentException> { MaterialName.constructFromPersisted(stored) }
    }
}
