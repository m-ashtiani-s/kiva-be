package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SlugTest {

    @ParameterizedTest
    @ValueSource(strings = ["shoulder", "mahak-shoulder-bag", "paeez-1405", "a1", "1405-sale", "x"])
    fun `lowercase letters, digits and single dashes with at least one letter are accepted as they are`(raw: String) {
        assertEquals(raw, Slug.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "12",                  // digits only: that is an id, never a slug
            "1405-1406",           // still no letter
            "Shoulder",            // uppercase
            " shoulder",           // not trimmed: a slug is an address, a stray space is a broken link
            "-shoulder",
            "shoulder-",
            "mahak--bag",
            "mahak_bag",
            "mahak bag",
            "کیف",
        ],
    )
    fun `anything else is SLUG_INVALID`(raw: String) {
        assertEquals(Result.failure(Slug.Failure.Invalid), Slug.makeNew(raw))
    }

    @Test
    fun `at most 100 characters`() {
        assertEquals(100, Slug.makeNew("a".repeat(100)).getOrNull()?.value?.length)
        assertEquals(Result.failure(Slug.Failure.Invalid), Slug.makeNew("a".repeat(101)))
    }

    @Test
    fun `the failure is an input problem without a field, the caller names the field`() {
        val failure = Slug.Failure.Invalid
        assertEquals("SLUG_INVALID", failure.code)
        assertEquals(FailureKind.INVALID, failure.kind)
        assertNull(failure.field)
    }

    @Test
    fun `constructFromPersisted keeps the stored value and compares by value`() {
        assertEquals("shoulder", Slug.constructFromPersisted("shoulder").value)
        assertEquals(Slug.makeNew("shoulder").getOrNull(), Slug.constructFromPersisted("shoulder"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["12", "Shoulder", ""])
    fun `a broken stored value is a bug, so constructFromPersisted throws instead of building it`(stored: String) {
        assertFailsWith<IllegalArgumentException> { Slug.constructFromPersisted(stored) }
    }
}
