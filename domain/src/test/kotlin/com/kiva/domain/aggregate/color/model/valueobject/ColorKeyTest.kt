package com.kiva.domain.aggregate.color.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ColorKeyTest {

    @ParameterizedTest
    @ValueSource(strings = ["lilac", "black", "dark-green", "rose2", "ab"])
    fun `a lowercase key starting with a letter is accepted as it is`(raw: String) {
        assertEquals(raw, ColorKey.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "a",          // too short: at least 2 characters
            "Lilac",      // uppercase
            "2tone",      // must start with a letter
            "-lilac",
            "dark green",
            "dark_green",
            "یاسی",
            " lilac",     // not trimmed
        ],
    )
    fun `anything else is COLOR_KEY_INVALID`(raw: String) {
        assertEquals(Result.failure(ColorKey.Failure.Invalid), ColorKey.makeNew(raw))
    }

    @Test
    fun `at most 31 characters`() {
        assertEquals(31, ColorKey.makeNew("a".repeat(31)).getOrNull()?.value?.length)
        assertEquals(Result.failure(ColorKey.Failure.Invalid), ColorKey.makeNew("a".repeat(32)))
    }

    @Test
    fun `the failure is an input problem without a field, the caller names the field`() {
        assertEquals("COLOR_KEY_INVALID", ColorKey.Failure.Invalid.code)
        assertEquals(FailureKind.INVALID, ColorKey.Failure.Invalid.kind)
        assertNull(ColorKey.Failure.Invalid.field)
    }

    @Test
    fun `constructFromPersisted keeps the stored value and compares by value`() {
        assertEquals(ColorKey.makeNew("lilac").getOrNull(), ColorKey.constructFromPersisted("lilac"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Lilac", "a", ""])
    fun `a broken stored value is a bug, so constructFromPersisted throws instead of building it`(stored: String) {
        assertFailsWith<IllegalArgumentException> { ColorKey.constructFromPersisted(stored) }
    }
}
