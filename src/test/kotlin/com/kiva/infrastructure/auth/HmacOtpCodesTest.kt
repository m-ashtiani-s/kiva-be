package com.kiva.infrastructure.auth

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.model.valueobject.Salt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HmacOtpCodesTest {

    private val pepper = ByteArray(32) { it.toByte() }
    private val codes = HmacOtpCodes(pepper)

    private val phone = IranianMobileNumber.constructFromPersisted("09121234567")
    private val otherPhone = IranianMobileNumber.constructFromPersisted("09121234568")
    private val code = OtpCode.of(48_213)
    private val salt = Salt(ByteArray(16) { 5 })

    @Test
    fun `the hash of a new code is what the right code typed later hashes to`() {
        val sent = codes.newCode(OtpPurpose.LOGIN, phone)
        val typed = OtpCode.makeNew(sent.code.value).getOrNull() ?: error("not a code")

        assertTrue(sent.hash.matches(codes.hash(typed, sent.salt, OtpPurpose.LOGIN, phone)))
    }

    @Test
    fun `the same code, salt, purpose and phone always hash the same`() {
        assertEquals(codes.hash(code, salt, OtpPurpose.LOGIN, phone), HmacOtpCodes(pepper.copyOf()).hash(code, salt, OtpPurpose.LOGIN, phone))
    }

    @Test
    fun `another salt or another pepper gives another hash`() {
        val hash = codes.hash(code, salt, OtpPurpose.LOGIN, phone)

        assertNotEquals(hash, codes.hash(code, Salt(ByteArray(16) { 6 }), OtpPurpose.LOGIN, phone))
        assertNotEquals(hash, HmacOtpCodes(ByteArray(32) { 1 }).hash(code, salt, OtpPurpose.LOGIN, phone))
    }

    @Test
    fun `a code never matches another purpose, phone or code`() {
        val loginHash = codes.hash(code, salt, OtpPurpose.LOGIN, phone)

        OtpPurpose.entries.filter { it != OtpPurpose.LOGIN }.forEach {
            assertFalse(loginHash.matches(codes.hash(code, salt, it, phone)), "matched $it")
        }
        assertFalse(loginHash.matches(codes.hash(code, salt, OtpPurpose.LOGIN, otherPhone)))
        assertFalse(loginHash.matches(codes.hash(OtpCode.of(48_214), salt, OtpPurpose.LOGIN, phone)))
    }

    @Test
    fun `of 100,000 new codes every one has exactly 5 digits, and codes starting with 0 are made too`() {
        val made = List(100_000) { codes.newCode(OtpPurpose.LOGIN, phone).code.value }

        assertTrue(made.all { code -> code.length == 5 && code.all { it in '0'..'9' } })
        assertTrue(made.any { it.startsWith("0") })
        // Drawn from all 100,000 codes: about 63,000 different ones are expected, a broken source gives far fewer.
        assertTrue(made.toSet().size > 60_000, "only ${made.toSet().size} different codes")
    }

    @Test
    fun `every new code gets its own salt`() {
        val salts = List(1_000) { codes.newCode(OtpPurpose.LOGIN, phone).salt }

        assertEquals(1_000, salts.toSet().size)
    }

    @Test
    fun `a pepper shorter than 32 bytes is refused`() {
        assertFailsWith<IllegalArgumentException> { HmacOtpCodes(ByteArray(31)) }
    }

    @Test
    fun `a new code never shows in a string`() {
        val sent = codes.newCode(OtpPurpose.LOGIN, phone)

        assertFalse(sent.code.value in sent.toString())
    }
}
