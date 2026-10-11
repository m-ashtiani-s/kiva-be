package com.kiva.domain.aggregate.otp.model.valueobject

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SaltAndCodeHashTest {

    @Test
    fun `a salt is 16 bytes and a hash 32, anything else is a bug`() {
        Salt(ByteArray(16))
        CodeHash(ByteArray(32))
        assertFailsWith<IllegalArgumentException> { Salt(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { CodeHash(ByteArray(31)) }
    }

    @Test
    fun `two of them are equal when their bytes are`() {
        assertEquals(Salt(ByteArray(16) { 7 }), Salt(ByteArray(16) { 7 }))
        assertNotEquals(Salt(ByteArray(16) { 7 }), Salt(ByteArray(16) { 8 }))
        assertEquals(CodeHash(ByteArray(32) { 7 }), CodeHash(ByteArray(32) { 7 }))
    }

    @Test
    fun `a hash matches only the same bytes`() {
        val hash = CodeHash(ByteArray(32) { it.toByte() })

        assertTrue(hash.matches(CodeHash(ByteArray(32) { it.toByte() })))
        assertFalse(hash.matches(CodeHash(ByteArray(32) { if (it == 31) 0 else it.toByte() })))
    }

    @Test
    fun `they keep their own copy, so changing the array afterwards changes nothing`() {
        val bytes = ByteArray(32) { 1 }
        val hash = CodeHash(bytes)

        bytes.fill(2)
        hash.toByteArray().fill(3)

        assertEquals(CodeHash(ByteArray(32) { 1 }), hash)
    }

    @Test
    fun `their bytes never show in a string`() {
        assertEquals("Salt(16 bytes)", Salt(ByteArray(16)).toString())
        assertEquals("CodeHash(32 bytes)", CodeHash(ByteArray(32)).toString())
    }
}
