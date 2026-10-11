package com.kiva.common.patch

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PatchTest {

    private data object TooShort : ResultFailure("TOO_SHORT", FailureKind.INVALID)

    private fun atLeastThree(raw: String): Result<Int, TooShort> =
        if (raw.length >= 3) Result.success(raw.length) else Result.failure(TooShort)

    @Test
    fun `a field that wasn't sent keeps the current value`() {
        assertEquals("سارا", Patch.Unchanged.applyTo("سارا"))
    }

    @Test
    fun `a sent value replaces the current one, and a sent null clears it`() {
        assertEquals("مریم", Patch.Set("مریم").applyTo("سارا"))
        assertEquals(null, Patch.Set<String?>(null).applyTo("سارا"))
    }

    @Test
    fun `mapResult checks a sent value and keeps it as a patch`() {
        assertEquals(Result.success(Patch.Set(4)), Patch.Set("کیوا").mapResult(::atLeastThree))
        assertEquals(Result.failure(TooShort), Patch.Set("کی").mapResult(::atLeastThree))
    }

    @Test
    fun `mapResult doesn't check a field that wasn't sent`() {
        val patch: Patch<String> = Patch.Unchanged
        assertEquals(Result.success(Patch.Unchanged), patch.mapResult<String, Int, TooShort> { fail("checked a field that wasn't sent") })
    }
}
