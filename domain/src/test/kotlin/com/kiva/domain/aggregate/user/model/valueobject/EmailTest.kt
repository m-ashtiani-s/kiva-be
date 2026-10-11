package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmailTest {

    @Test
    fun `an address is kept trimmed and in lower case`() {
        assertEquals("sara@x.com", Email.makeNew("  Sara@X.com ").getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["sara.m+shop@mail.example.ir", "s_1@kiva.co", "a@b-c.io"])
    fun `everyday addresses are accepted`(raw: String) {
        assertEquals(raw, Email.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "sara", "sara@", "@x.com", "sara@x", "sa ra@x.com", "sara@@x.com", "سارا@x.com", "sara@x.c"])
    fun `anything else is EMAIL_INVALID on the email field`(raw: String) {
        assertEquals(Result.failure(Email.Failure.Invalid), Email.makeNew(raw))
        assertEquals("EMAIL_INVALID", Email.Failure.Invalid.code)
        assertEquals("email", Email.Failure.Invalid.field)
    }

    @Test
    fun `at most 254 characters, and the failure tells the client the limit`() {
        val domain = "@x.com"
        assertEquals(254, Email.makeNew("a".repeat(254 - domain.length) + domain).getOrNull()?.value?.length)
        assertEquals(Result.failure(Email.Failure.TooLong), Email.makeNew("a".repeat(255 - domain.length) + domain))
        assertEquals(mapOf<String, Any>("maxLength" to 254), Email.Failure.TooLong.meta)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Sara@x.com", "sara"])
    fun `a broken stored value is a bug, so constructFromPersisted throws`(stored: String) {
        assertFailsWith<IllegalArgumentException> { Email.constructFromPersisted(stored) }
    }
}
