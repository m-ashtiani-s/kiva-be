package com.kiva.application.usecase.content

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the FAQ search matches and how it marks a question (roadmap phase 5, decision 17). */
class FaqSearchTest {

    private fun search(q: String) = FaqSearch.of(q).getOrNull()!!

    @Test
    fun `Arabic letters, Persian digits and capitals don't stop a match, either way round`() {
        assertTrue(search("رزرو").matches("رزرو ۴ روزه چطور کار می‌کنه؟"))
        assertTrue(search("كيف").matches("کیف دوشی"))
        assertTrue(search("4 روزه").matches("رزرو ۴ روزه"))
        assertTrue(search("TIPAX").matches("ارسال با tipax"))
        assertFalse(search("پست").matches("تیپاکس"))
    }

    @Test
    fun `folding keeps the length, so a match's place is its place in the text`() {
        val text = "رزرو ۴ روزه؛ رزرو دوباره"

        assertEquals(text.length, FaqSearch.fold(text).length)
        assertEquals(listOf(0..3, 13..16), search("رزرو").rangesIn(text))
    }

    @Test
    fun `a question is escaped and marked - an editor's tag in it is text, never a tag`() {
        assertEquals("&lt;b&gt;<mark>رزرو</mark>&lt;/b&gt; &amp; &quot;بیشتر&quot;", search("رزرو").markText("<b>رزرو</b> & \"بیشتر\""))
    }

    @Test
    fun `an empty or blank q is no search - over 100 letters is TEXT_TOO_LONG on q`() {
        assertNull(FaqSearch.of(null).getOrNull())
        assertNull(FaqSearch.of("   ").getOrNull())
        val failure = FaqSearch.of("ر".repeat(101)).failureOrNull()!!
        assertEquals("q TEXT_TOO_LONG", "${failure.field} ${failure.code}")
    }
}
