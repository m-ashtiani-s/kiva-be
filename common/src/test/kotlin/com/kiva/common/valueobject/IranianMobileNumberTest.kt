package com.kiva.common.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IranianMobileNumberTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "09121234567",
            "9121234567",
            "+989121234567",
            "00989121234567",
            "989121234567",
            "+98 912 123 4567",
            "+98 0912 123 4567",  // common mistake: country code AND the leading 0
            "0912-123-4567",
            "(0912) 123 4567",
            "۰۹۱۲۱۲۳۴۵۶۷",        // Persian keyboard
            "٠٩١٢١٢٣٤٥٦٧",        // Arabic keyboard
            "  09121234567  ",
        ],
    )
    fun `every common way of writing the number is stored the same way`(raw: String) {
        assertEquals("09121234567", IranianMobileNumber.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", " - "])
    fun `empty input is PHONE_REQUIRED`(raw: String) {
        assertEquals(Result.failure(IranianMobileNumber.Failure.Blank), IranianMobileNumber.makeNew(raw))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0912123456",      // one digit short
            "091212345678",    // one digit too many
            "08121234567",     // landline-like, not starting with 9
            "02188776655",     // Tehran landline
            "0912abc4567",
            "+19121234567",    // another country
        ],
    )
    fun `anything else is PHONE_INVALID`(raw: String) {
        assertEquals(Result.failure(IranianMobileNumber.Failure.Invalid), IranianMobileNumber.makeNew(raw))
    }

    @Test
    fun `a leading 98 is only treated as country code when a full number follows`() {
        // 10 digits starting with 98: a national number, not "98" + "12345678"
        assertEquals("09812345678", IranianMobileNumber.makeNew("9812345678").getOrNull()?.value)
    }

    @Test
    fun `failures use the contract field name and code`() {
        assertEquals("phone", IranianMobileNumber.Failure.Invalid.field)
        assertEquals("PHONE_INVALID", IranianMobileNumber.Failure.Invalid.code)
    }

    @Test
    fun `the same number typed differently is equal`() {
        assertEquals(IranianMobileNumber.makeNew("+98 912 123 4567"), IranianMobileNumber.makeNew("۰۹۱۲۱۲۳۴۵۶۷"))
    }

    @Test
    fun `masked keeps the first and last four digits, for logs`() {
        assertEquals("0912***4567", IranianMobileNumber.makeNew("09121234567").getOrNull()?.masked())
    }

    @Test
    fun `a stored number is read back as it is`() {
        assertEquals(IranianMobileNumber.makeNew("09121234567").getOrNull(), IranianMobileNumber.constructFromPersisted("09121234567"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["9121234567", "+989121234567", "0912123456", "08121234567", "۰۹۱۲۱۲۳۴۵۶۷"])
    fun `a stored number not in the stored form is a bug, so constructFromPersisted throws`(stored: String) {
        assertFailsWith<IllegalArgumentException> { IranianMobileNumber.constructFromPersisted(stored) }
    }

    @Test
    fun `the number itself never ends up in the error of a broken stored value`() {
        val error = assertFailsWith<IllegalArgumentException> { IranianMobileNumber.constructFromPersisted("0812123456") }
        assertFalse("0812123456" in error.message.orEmpty())
    }
}
