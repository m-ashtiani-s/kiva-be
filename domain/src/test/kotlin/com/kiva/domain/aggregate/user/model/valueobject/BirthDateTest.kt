package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BirthDateTest {

    private val today = LocalDate.of(2026, 10, 9)

    @Test
    fun `from 1900-01-01 up to today, both included`() {
        assertEquals(LocalDate.of(1900, 1, 1), BirthDate.makeNew(LocalDate.of(1900, 1, 1), today).getOrNull()?.value)
        assertEquals(LocalDate.of(1996, 9, 10), BirthDate.makeNew(LocalDate.of(1996, 9, 10), today).getOrNull()?.value)
        assertEquals(today, BirthDate.makeNew(today, today).getOrNull()?.value)
    }

    @Test
    fun `tomorrow and 1899 are BIRTH_DATE_INVALID on the birthDate field`() {
        assertEquals(Result.failure(BirthDate.Failure.OutOfRange), BirthDate.makeNew(today.plusDays(1), today))
        assertEquals(Result.failure(BirthDate.Failure.OutOfRange), BirthDate.makeNew(LocalDate.of(1899, 12, 31), today))
        assertEquals("BIRTH_DATE_INVALID", BirthDate.Failure.OutOfRange.code)
        assertEquals("birthDate", BirthDate.Failure.OutOfRange.field)
    }

    @Test
    fun `a stored date before 1900 is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { BirthDate.constructFromPersisted(LocalDate.of(1899, 12, 31)) }
    }
}
