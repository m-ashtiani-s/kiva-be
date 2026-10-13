package com.kiva.application.port

import com.kiva.common.valueobject.UserId
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class IdempotentRequestTest {

    private val hash = RequestHash.of("""{"body":{},"path":{}}""")
    private val sara = UserId.constructFromPersisted(7)

    private fun refused(raw: String?): Pair<String?, String>? =
        IdempotentRequest.makeNew(sara, IdempotencyScope.PLACE_ORDER, raw, hash).failureOrNull()?.let { it.field to it.code }

    @Test
    fun `a missing or malformed key is 400 on the header's own name`() {
        assertEquals("Idempotency-Key" to "IDEMPOTENCY_KEY_REQUIRED", refused(null))
        assertEquals("Idempotency-Key" to "IDEMPOTENCY_KEY_REQUIRED", refused("  "))
        listOf("abc", "1-2-3-4-5", "7f3a9c2e1b", "7f3a9c2e-1b4d-4c8a-9e6f-12345678901").forEach {
            assertEquals("Idempotency-Key" to "IDEMPOTENCY_KEY_INVALID", refused(it), it)
        }
    }

    @Test
    fun `a UUID is the key, in any case and with spaces around it`() {
        val lower = IdempotentRequest.makeNew(sara, IdempotencyScope.PLACE_ORDER, "7f3a9c2e-1b4d-4c8a-9e6f-0123456789ab", hash).getOrNull()!!
        val upper = IdempotentRequest.makeNew(sara, IdempotencyScope.PLACE_ORDER, " 7F3A9C2E-1B4D-4C8A-9E6F-0123456789AB ", hash).getOrNull()!!

        assertEquals(UUID.fromString("7f3a9c2e-1b4d-4c8a-9e6f-0123456789ab"), lower.key.value)
        assertEquals(lower, upper)
    }

    @Test
    fun `a request's fingerprint is the SHA-256 of its canonical form - equal for equal text, different otherwise`() {
        assertEquals(RequestHash.of("a"), RequestHash.of("a"))
        assertNotEquals(RequestHash.of("a"), RequestHash.of("b"))
        assertEquals(32, RequestHash.of("a").toByteArray().size)
        assertFailsWith<IllegalArgumentException> { RequestHash.constructFromPersisted(ByteArray(16)) }
        assertFalse("ca978112" in "${RequestHash.of("a")}")
    }
}
