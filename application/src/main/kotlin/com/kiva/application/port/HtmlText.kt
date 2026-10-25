package com.kiva.application.port

/**
 * Reads and marks the text of sanitized HTML (a FAQ answer) the way a browser would parse it, so a match is looked for in
 * what a reader sees and never inside a tag or an address.
 */
interface HtmlText {

    /** The text a reader sees, without tags. */
    fun plainText(html: String): String

    /**
     * [html] with `<mark>` around each range [find] gives for the text of each text node, and nothing else changed; a
     * match never crosses a tag.
     */
    fun mark(html: String, find: (String) -> List<IntRange>): String
}
