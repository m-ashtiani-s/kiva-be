package com.kiva.common.railway

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InputProblemsTest {

    private data object NameBlank : ResultFailure("NAME_BLANK", FailureKind.INVALID, field = "name")
    private data object PriceNegative : ResultFailure("PRICE_NEGATIVE", FailureKind.INVALID, field = "price")
    private data object ColorInvalid : ResultFailure("COLOR_INVALID", FailureKind.INVALID, field = "colorKey")

    @Test
    fun `groups at any depth are opened, in order`() {
        val problems = listOf(InputProblems(listOf(NameBlank, InputProblems(listOf(PriceNegative)))), ColorInvalid)

        assertEquals(listOf<ResultFailure>(NameBlank, PriceNegative, ColorInvalid), problems.flattenGroups())
    }

    @Test
    fun `a problem with a field of its own keeps what it holds, so its path stays`() {
        val color = FieldFailure("variants.1", InputProblems(listOf(ColorInvalid)))

        assertEquals(listOf<ResultFailure>(color), listOf(InputProblems(listOf(color))).flattenGroups())
    }
}
