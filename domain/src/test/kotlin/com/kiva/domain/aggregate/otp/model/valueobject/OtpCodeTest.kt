package com.kiva.domain.aggregate.otp.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OtpCodeTest {

    @ParameterizedTest
    @ValueSource(strings = ["48213", "۴۸۲۱۳", "٤٨٢١٣", " 48213 "])
    fun `a typed code is 5 digits from any keyboard, spaces around it trimmed`(raw: String) {
        assertEquals("48213", OtpCode.makeNew(raw).getOrNull()?.value)
    }

    @Test
    fun `leading zeros are part of the code`() {
        assertEquals("00042", OtpCode.makeNew("00042").getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "4821", "482130", "48a13", "4 8213", "-4821", "４８２１３"])
    fun `anything but exactly 5 digits is OTP_CODE_FORMAT_INVALID, before any comparison`(raw: String) {
        assertEquals(Result.failure(OtpCode.Failure.InvalidFormat), OtpCode.makeNew(raw))
    }

    @Test
    fun `the format failure names no field, the caller does, and tells the client the length`() {
        assertEquals("OTP_CODE_FORMAT_INVALID", OtpCode.Failure.InvalidFormat.code)
        assertNull(OtpCode.Failure.InvalidFormat.field)
        assertEquals(mapOf<String, Any>("codeLength" to 5), OtpCode.Failure.InvalidFormat.meta)
    }

    @Test
    fun `a number from the random source is written with 5 digits`() {
        assertEquals("00042", OtpCode.of(42).value)
        assertEquals("00000", OtpCode.of(0).value)
        assertEquals("99999", OtpCode.of(99_999).value)
    }

    @Test
    fun `a number outside 0 to 99999 is a bug`() {
        assertFailsWith<IllegalArgumentException> { OtpCode.of(100_000) }
        assertFailsWith<IllegalArgumentException> { OtpCode.of(-1) }
    }

    @Test
    fun `the code never shows in a string, so a log by mistake writes stars`() {
        val code = OtpCode.of(48_213)

        assertEquals("OtpCode(*****)", "$code")
        assertFalse("48213" in "sent $code")
        assertFalse("48213" in listOf(code).toString())
    }
}
