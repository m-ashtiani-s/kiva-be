package com.kiva.infrastructure.common.html

import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * [HtmlSanitizer] with jsoup's allowlist cleaner: it parses the HTML the way a browser would and keeps only what is on
 * the list, so a trick the list doesn't know is dropped, not let through.
 *
 * The list is the contract's (HtmlString): `p, b, strong, em, a, ul, ol, li, br, mark, h2, h3, blockquote`, no
 * attribute but `href` on `a`. A link keeps its href only when it goes to a whole `http`/`https` address, or to a path
 * of this site (`/faq#reserve`) or a place on the page (`#top`) (roadmap phase 5, decisions 6 and 19); `//evil.com` and
 * `/\evil.com`, which a browser reads as another site, a `javascript:` link, and anything else lose their href.
 * The text of a dropped tag stays, as plain text; a script's or style's content goes with it.
 */
object JsoupHtmlSanitizer : HtmlSanitizer {

    // jsoup checks a relative link's protocol against this address and keeps the link as written (preserveRelativeLinks);
    // the address itself never appears in the output.
    private const val CHECK_BASE = "https://kiva.invalid/"

    private val allowed: Safelist = Safelist()
        .addTags("p", "b", "strong", "em", "a", "ul", "ol", "li", "br", "mark", "h2", "h3", "blockquote")
        .addAttributes("a", "href")
        .addProtocols("a", "href", "http", "https")
        .preserveRelativeLinks(true)

    // Written back as it came in, not re-indented: the stored text stays the editor's.
    private val output = Document.OutputSettings().prettyPrint(false)

    override fun clean(html: String): String {
        val clean = Cleaner(allowed).clean(Jsoup.parseBodyFragment(html, CHECK_BASE))
        clean.select("a[href]").forEach { link ->
            val href = link.attr("href").trim()
            if (isAllowedHref(href)) link.attr("href", href) else link.removeAttr("href")
        }
        return clean.outputSettings(output).body().html()
    }

    /** A whole http(s) address, a path of this site (not `//` or `/\`), or a place on the page; no control character. */
    fun isAllowedHref(href: String): Boolean {
        if (href.any { it.code < 0x20 || it.code == 0x7f }) return false
        val lower = href.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://") ||
            (href.startsWith("/") && !href.startsWith("//") && !href.startsWith("/\\")) ||
            href.startsWith("#")
    }
}
