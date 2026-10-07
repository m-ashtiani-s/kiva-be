package com.kiva.infrastructure.common.html

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The contract's HtmlString: what an editor may write stays as it is, everything that could run in a page goes. */
class JsoupHtmlSanitizerTest {

    private fun clean(html: String) = JsoupHtmlSanitizer.clean(html)

    @Test
    fun `every tag the contract allows stays, exactly as written`() {
        val html = "<h2>کیف دوشی ماهک</h2><p>چرم <strong>طبیعی</strong> و <b>محکم</b>، <em>سبک</em> و <mark>جادار</mark>.<br>" +
            "<a href=\"https://kiva.ir/faq#care\">نگهداری</a></p><ul><li>یک</li></ul><ol><li>دو</li></ol>" +
            "<h3>نکته</h3><blockquote>نقل قول</blockquote>"

        assertEquals(html, clean(html))
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "a script goes, its content too  | <p>کیف</p><script>alert(1)</script>           | <p>کیف</p>",
            "an event attribute goes         | <p onclick=\"alert(1)\">کیف</p>                | <p>کیف</p>",
            "an image with onerror goes      | <p>کیف<img src=x onerror=\"alert(1)\"></p>     | <p>کیف</p>",
            "a javascript link loses its href| <a href=\"javascript:alert(1)\">کلیک</a>        | <a>کلیک</a>",
            "an iframe goes                  | <p>کیف</p><iframe src=\"https://evil.example\"></iframe> | <p>کیف</p>",
            "a style tag goes, its rules too | <style>p{display:none}</style><p>کیف</p>     | <p>کیف</p>",
            "a style attribute goes          | <p style=\"color:red\">کیف</p>                 | <p>کیف</p>",
            "a tag not on the list keeps its text | <div><span>کیف</span></div>          | کیف",
            "a relative link loses its href  | <a href=\"/products\">همه</a>                   | <a>همه</a>",
            "an http link stays              | <a href=\"http://kiva.ir\">کیوا</a>             | <a href=\"http://kiva.ir\">کیوا</a>",
        ],
    )
    fun `what could run in the page goes`(case: String, dirty: String, safe: String) {
        assertEquals(safe, clean(dirty), case)
    }

    @Test
    fun `text with nothing safe in it comes back blank`() {
        assertEquals("", clean("<script>alert(1)</script>").trim())
    }
}
