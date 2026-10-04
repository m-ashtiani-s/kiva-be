package com.kiva.common.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DigitsTest {

    @Test
    fun `Persian digits become English`() {
        assertEquals("0123456789", "۰۱۲۳۴۵۶۷۸۹".normalizeDigits())
    }

    @Test
    fun `Arabic digits become English`() {
        assertEquals("0123456789", "٠١٢٣٤٥٦٧٨٩".normalizeDigits())
    }

    @Test
    fun `mixed text keeps everything that is not a digit`() {
        assertEquals("کد 123-45 abc", "کد ۱۲3-٤5 abc".normalizeDigits())
    }

    @Test
    fun `English digits and empty text are unchanged`() {
        assertEquals("09121234567", "09121234567".normalizeDigits())
        assertEquals("", "".normalizeDigits())
    }
}
