package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PersonNameTest {

    @Test
    fun `an Arabic keyboard's name is kept in Persian letters`() {
        assertEquals("علی", PersonName.makeNew("علي").getOrNull()?.value)
        assertEquals("کاظم", PersonName.makeNew("كاظم").getOrNull()?.value)
    }

    @Test
    fun `spaces around the name are trimmed and repeated spaces inside collapse to one`() {
        assertEquals("سارا محمدی", PersonName.makeNew("  سارا   محمدی\t").getOrNull()?.value)
    }

    @Test
    fun `a half-space inside a name is kept`() {
        assertEquals("عبدالله‌زاده", PersonName.makeNew("عبدالله‌زاده").getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "‌"])
    fun `an empty name is PERSON_NAME_REQUIRED, because clearing a name is a null`(raw: String) {
        assertEquals(Result.failure(PersonName.Failure.Blank), PersonName.makeNew(raw))
        assertEquals("PERSON_NAME_REQUIRED", PersonName.Failure.Blank.code)
    }

    @Test
    fun `at most 50 characters, and the failure tells the client the limit`() {
        assertEquals(50, PersonName.makeNew("س".repeat(50)).getOrNull()?.value?.length)
        assertEquals(Result.failure(PersonName.Failure.TooLong), PersonName.makeNew("س".repeat(51)))
        assertEquals(mapOf<String, Any>("maxLength" to 50), PersonName.Failure.TooLong.meta)
    }

    @ParameterizedTest
    @ValueSource(strings = ["<b>سارا</b>", "سارا>", "سا\u0000را", "سا\u0007را"])
    fun `angle brackets and control characters are PERSON_NAME_INVALID`(raw: String) {
        assertEquals(Result.failure(PersonName.Failure.InvalidCharacters), PersonName.makeNew(raw))
        assertEquals("PERSON_NAME_INVALID", PersonName.Failure.InvalidCharacters.code)
    }

    @Test
    fun `no field of its own, the caller names it`() {
        assertNull(PersonName.Failure.Blank.field)
    }

    @ParameterizedTest
    @ValueSource(strings = [" ", "<b>"])
    fun `a broken stored value is a bug, so constructFromPersisted throws`(stored: String) {
        assertFailsWith<IllegalArgumentException> { PersonName.constructFromPersisted(stored) }
    }
}
