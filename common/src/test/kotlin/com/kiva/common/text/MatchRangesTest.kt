package com.kiva.common.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MatchRangesTest {

    private fun marked(text: String, vararg words: String) = MatchRanges.mark(text, MatchRanges.of(text, words.map(MatchRanges::fold)))

    @Test
    fun `a word before a half-space marks the original letters and keeps the half-space outside`() {
        assertEquals("<mark>کراس</mark>‌بادی نیلا", marked("کراس‌بادی نیلا", "کراس"))
    }

    @Test
    fun `an Arabic yeh in the search finds the Persian one and the stored letters are kept`() {
        assertEquals("کیف <mark>مشکی</mark>", marked("کیف مشکی", "مشكي"))
        assertEquals("<mark>كيف</mark> قدیمی", marked("كيف قدیمی", "کیف"))
    }

    @Test
    fun `Persian digits in the name match Latin ones in the search`() {
        assertEquals("کوله <mark>۲</mark>۴ لیتری", marked("کوله ۲۴ لیتری", "2"))
        assertEquals("کوله <mark>۲۴</mark> لیتری", marked("کوله ۲۴ لیتری", "24"))
    }

    @Test
    fun `two overlapping words are one mark - a word twice is marked twice`() {
        assertEquals("<mark>کراس‌با</mark>دی", marked("کراس‌بادی", "کراس", "س‌با"))
        assertEquals("<mark>ماه</mark> و <mark>ماه</mark>ک", marked("ماه و ماهک", "ماه"))
    }

    @Test
    fun `everything but the marks is escaped - a name with a tag never becomes one`() {
        assertEquals("&lt;b&gt;<mark>کیف</mark>&lt;/b&gt; &amp; &quot;کوله&quot;", marked("<b>کیف</b> & \"کوله\"", "کیف"))
        assertEquals("بی‌نشان &lt;script&gt;", marked("بی‌نشان <script>", "نیست"))
    }
}
