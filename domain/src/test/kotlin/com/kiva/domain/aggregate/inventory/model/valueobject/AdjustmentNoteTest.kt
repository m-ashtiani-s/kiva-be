package com.kiva.domain.aggregate.inventory.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdjustmentNoteTest {

    @Test
    fun `a note is trimmed and kept`() {
        assertEquals("بارنامه ۲۳۴", AdjustmentNote.makeNew("  بارنامه ۲۳۴ ").getOrNull()?.value)
    }

    @Test
    fun `missing or blank means no note, not an error`() {
        assertNull(AdjustmentNote.makeNew(null).getOrNull())
        assertNull(AdjustmentNote.makeNew("   ").getOrNull())
        assertEquals(true, AdjustmentNote.makeNew("   ").isSuccess)
    }

    @Test
    fun `more than 300 characters is ADJUSTMENT_NOTE_TOO_LONG on the note field`() {
        assertEquals(Result.failure(AdjustmentNote.Failure.TooLong), AdjustmentNote.makeNew("ب".repeat(301)))
        assertEquals("note", AdjustmentNote.Failure.TooLong.field)
    }

    @Test
    fun `a blank stored note is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { AdjustmentNote.constructFromPersisted(" ") }
    }
}
