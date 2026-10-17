package com.kiva.domain.aggregate.material.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MaterialValueObjectsTest {

    @Test
    fun `a family is one of the contract's values, exactly - anything else is MATERIAL_FAMILY_INVALID on family`() {
        assertEquals(Result.success(MaterialFamily.CANVAS), MaterialFamily.makeNew("CANVAS"))

        val failure = MaterialFamily.makeNew("canvas").failureOrNull()

        assertEquals("MATERIAL_FAMILY_INVALID", failure?.code)
        assertEquals("family", failure?.field)
    }

    @Test
    fun `a note is trimmed, blank is none, at most 1000 - more is MATERIAL_NOTE_TOO_LONG`() {
        assertEquals("سبک و ضدآب", MaterialNote.makeNew(" سبک و ضدآب ").getOrNull()?.value)
        assertEquals(Result.success(null), MaterialNote.makeNew(""))
        assertEquals(1000, MaterialNote.makeNew("ک".repeat(1000)).getOrNull()?.value?.length)

        val failure = MaterialNote.makeNew("ک".repeat(1001)).failureOrNull()

        assertEquals("MATERIAL_NOTE_TOO_LONG", failure?.code)
        assertEquals("description", failure?.field)
    }

    @Test
    fun `ten care lines, each trimmed and up to 200, are kept in order - null is none`() {
        val lines = (1..10).map { " خط $it " }

        assertEquals((1..10).map { "خط $it" }, CareInstructions.makeNew(lines).getOrNull()?.items)
        assertEquals(CareInstructions.NONE, CareInstructions.makeNew(null).getOrNull())
    }

    @Test
    fun `eleven lines, a blank line or a line of 201 is CARE_INSTRUCTIONS_INVALID with both limits`() {
        val tooMany = CareInstructions.makeNew((1..11).map { "خط $it" }).failureOrNull()
        val blank = CareInstructions.makeNew(listOf("خط", "  ")).failureOrNull()
        val tooLong = CareInstructions.makeNew(listOf("ک".repeat(201))).failureOrNull()

        listOf(tooMany, blank, tooLong).forEach {
            assertEquals("CARE_INSTRUCTIONS_INVALID", it?.code)
            assertEquals("careInstructions", it?.field)
            assertEquals(mapOf<String, Any>("maxItems" to 10, "maxLength" to 200), it?.meta)
        }
    }

    @Test
    fun `broken stored values are a bug`() {
        assertFailsWith<IllegalArgumentException> { MaterialNote.constructFromPersisted("") }
        assertFailsWith<IllegalArgumentException> { CareInstructions.constructFromPersisted(List(11) { "x" }) }
    }
}
