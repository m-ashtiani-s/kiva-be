package com.kiva.domain.aggregate.product.model.valueobject

/**
 * Makes rich text safe to put in a page as HTML: only the tags the contract allows stay, and everything else goes
 * (scripts, event attributes like `onerror`, `javascript:` links, iframes).
 *
 * The domain says it must happen ([ProductDescription.makeNew] takes one); infrastructure does it with a real HTML
 * parser, because cleaning HTML by hand or by regex always misses a trick.
 */
fun interface HtmlSanitizer {
    /** The same text with only safe HTML left. Blank when nothing safe was in it. */
    fun clean(html: String): String
}
