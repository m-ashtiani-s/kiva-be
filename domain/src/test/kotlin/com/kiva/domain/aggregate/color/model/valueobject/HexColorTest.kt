package com.kiva.domain.aggregate.color.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HexColorTest {

    @ParameterizedTest
    @ValueSource(strings = ["#C8B6E2", "#c8b6e2", " #c8B6e2 "])
    fun `a hash and six hex digits is accepted, always stored trimmed and uppercase`(raw: String) {
        assertEquals("#C8B6E2", HexColor.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "C8B6E2", "#C8B6E", "#C8B6E2F", "#GGGGGG", "#C8 B6E2", "rgb(200,182,226)"])
    fun `anything else is HEX_COLOR_INVALID on the hex field`(raw: String) {
        assertEquals(Result.failure(HexColor.Failure.Invalid), HexColor.makeNew(raw))
        assertEquals("HEX_COLOR_INVALID", HexColor.Failure.Invalid.code)
        assertEquals("hex", HexColor.Failure.Invalid.field)
    }

    @Test
    fun `constructFromPersisted keeps the stored value as it is, lowercase included`() {
        assertEquals("#c8b6e2", HexColor.constructFromPersisted("#c8b6e2").value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["C8B6E2", ""])
    fun `a broken stored value is a bug, so constructFromPersisted throws instead of building it`(stored: String) {
        assertFailsWith<IllegalArgumentException> { HexColor.constructFromPersisted(stored) }
    }
}
