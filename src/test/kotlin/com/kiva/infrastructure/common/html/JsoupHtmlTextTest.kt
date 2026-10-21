package com.kiva.infrastructure.common.html

import com.kiva.application.usecase.content.FaqSearch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Marking an answer: only what a reader sees is marked, never a tag's name, an attribute or an address. */
class JsoupHtmlTextTest {

    private fun mark(html: String, q: String) = JsoupHtmlText.mark(html, FaqSearch.of(q).getOrNull()!!::rangesIn)

    @Test
    fun `a match in the text is marked, in each text node, the rest as it was`() {
        assertEquals(
            "<p>با <mark>رزرو</mark> ۴ روزه <b>همه‌ی <mark>رزرو</mark>ها</b> یکجا می‌رسن</p>",
            mark("<p>با رزرو ۴ روزه <b>همه‌ی رزروها</b> یکجا می‌رسن</p>", "رزرو"),
        )
    }

    @Test
    fun `a tag's name and a link's address are never marked`() {
        assertEquals("<p><strong>پررنگ</strong> <a href=\"/faq#reserve\">لینک</a></p>", mark("<p><strong>پررنگ</strong> <a href=\"/faq#reserve\">لینک</a></p>", "strong"))
        assertEquals("<p><a href=\"/faq#reserve\"><mark>reserve</mark></a></p>", mark("<p><a href=\"/faq#reserve\">reserve</a></p>", "reserve"))
    }

    @Test
    fun `text with characters HTML needs escaped stays escaped`() {
        assertEquals("<p>&lt;<mark>رزرو</mark>&gt; &amp;</p>", mark("<p>&lt;رزرو&gt; &amp;</p>", "رزرو"))
    }

    @Test
    fun `the plain text has no tags`() {
        assertEquals("با رزرو یکجا", JsoupHtmlText.plainText("<p>با <b>رزرو</b> <a href=\"/x\">یکجا</a></p>"))
    }
}
