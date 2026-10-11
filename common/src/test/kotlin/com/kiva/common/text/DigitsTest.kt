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

    @Test
    fun `normalizePersian makes Arabic keyboard letters Persian`() {
        assertEquals("کیف یاسی", "كيف ياسى".normalizePersian())
    }

    @Test
    fun `normalizePersian also normalizes digits`() {
        assertEquals("کیف 2", "کیف ۲".normalizePersian())
    }

    @Test
    fun `normalizePersian cleans half-spaces and spaces`() {
        val zwnj = '‌'
        assertEquals("کیف${zwnj}ها", "کیف${zwnj}${zwnj}ها".normalizePersian())    // double half-space -> one
        assertEquals("کیف دوشی", "کیف$zwnj دوشی".normalizePersian())              // half-space next to a space
        assertEquals("کیف دوشی", "  کیف   دوشی  ".normalizePersian())             // extra spaces
        assertEquals("کیف", "${zwnj}کیف$zwnj".normalizePersian())                  // half-space at the ends
    }

    @Test
    fun `normalizePersian keeps a correct half-space`() {
        assertEquals("می‌خوام", "می‌خوام".normalizePersian())
    }

    @Test
    fun `normalizePersianLetters cleans letters and spaces like normalizePersian but keeps the digits as typed`() {
        assertEquals("خیابان ولیعصر، پلاک ۱۲", "  خيابان ولیعصر،   پلاک ۱۲ ".normalizePersianLetters())
        assertEquals("کوچه ٣ و 4", "كوچه ٣ و 4".normalizePersianLetters())
        assertEquals("پلاک 12", "پلاک ۱۲".normalizePersian())
    }
}
