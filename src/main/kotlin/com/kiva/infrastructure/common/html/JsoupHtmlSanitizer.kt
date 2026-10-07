package com.kiva.infrastructure.common.html

import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

/**
 * [HtmlSanitizer] with jsoup's allowlist cleaner: it parses the HTML the way a browser would and keeps only what is on
 * the list, so a trick the list doesn't know is dropped, not let through.
 *
 * The list is the contract's (HtmlString): `p, b, strong, em, a, ul, ol, li, br, mark, h2, h3, blockquote`, no
 * attribute but `href` on `a`, and only to `http` or `https` addresses (a relative or `javascript:` link loses its href).
 * The text of a dropped tag stays, as plain text; a script's or style's content goes with it.
 */
object JsoupHtmlSanitizer : HtmlSanitizer {

    private val allowed: Safelist = Safelist()
        .addTags("p", "b", "strong", "em", "a", "ul", "ol", "li", "br", "mark", "h2", "h3", "blockquote")
        .addAttributes("a", "href")
        .addProtocols("a", "href", "http", "https")

    // Written back as it came in, not re-indented: the stored text stays the editor's.
    private val output = Document.OutputSettings().prettyPrint(false)

    override fun clean(html: String): String = Jsoup.clean(html, "", allowed, output)
}
