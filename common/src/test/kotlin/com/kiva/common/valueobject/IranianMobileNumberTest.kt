package com.kiva.common.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

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
        assertEquals("09121234567", IranianMobileNumber.of(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", " - "])
    fun `empty input is MOBILE_BLANK`(raw: String) {
        assertEquals(Result.failure(IranianMobileNumber.Failure.Blank), IranianMobileNumber.of(raw))
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
    fun `anything else is MOBILE_INVALID`(raw: String) {
        assertEquals(Result.failure(IranianMobileNumber.Failure.Invalid), IranianMobileNumber.of(raw))
    }

    @Test
    fun `a leading 98 is only treated as country code when a full number follows`() {
        // 10 digits starting with 98: a national number, not "98" + "12345678"
        assertEquals("09812345678", IranianMobileNumber.of("9812345678").getOrNull()?.value)
    }

    @Test
    fun `failures point at the mobile field`() {
        assertEquals("mobile", IranianMobileNumber.Failure.Invalid.field)
        assertEquals("MOBILE_INVALID", IranianMobileNumber.Failure.Invalid.code)
    }

    @Test
    fun `the same number typed differently is equal`() {
        assertEquals(IranianMobileNumber.of("+98 912 123 4567"), IranianMobileNumber.of("۰۹۱۲۱۲۳۴۵۶۷"))
    }
}
