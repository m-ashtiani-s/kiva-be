package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class UserIdTest {

    @Test
    fun `a real account id is any positive number`() {
        assertEquals(1024L, UserId.makeNew(1024).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `0 and below are not accounts, so makeNew refuses them with USER_ID_INVALID`(raw: Long) {
        assertEquals(Result.failure(UserId.Failure.Invalid), UserId.makeNew(raw))
        assertEquals("USER_ID_INVALID", UserId.Failure.Invalid.code)
        assertEquals(FailureKind.INVALID, UserId.Failure.Invalid.kind)
    }

    @Test
    fun `SYSTEM is 0, can be read back from storage, and is never a real account`() {
        assertEquals(0L, UserId.SYSTEM.value)
        assertEquals(UserId.SYSTEM, UserId.constructFromPersisted(0))
        assertNotEquals(UserId.SYSTEM, UserId.constructFromPersisted(1))
    }

    @Test
    fun `a negative stored id is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { UserId.constructFromPersisted(-1) }
    }
}
