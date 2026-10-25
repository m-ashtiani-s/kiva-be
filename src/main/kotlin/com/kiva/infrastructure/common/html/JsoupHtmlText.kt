package com.kiva.infrastructure.common.html

import com.kiva.application.port.HtmlText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * [HtmlText] with jsoup: the HTML is parsed as a browser would, and only text nodes are read or marked, so a match is
 * never looked for in a tag's name, an attribute or an address (roadmap phase 5, decision 17). The HTML is already
 * sanitized (JsoupHtmlSanitizer); what this writes back is the same HTML with `<mark>` added, its text escaped by jsoup.
 */
object JsoupHtmlText : HtmlText {

    private val output = Document.OutputSettings().prettyPrint(false)

    override fun plainText(html: String): String = Jsoup.parseBodyFragment(html).body().text()

    override fun mark(html: String, find: (String) -> List<IntRange>): String {
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings(output)
        // Collected first: marking a node changes the tree being walked.
        val texts = mutableListOf<TextNode>()
        document.body().forEachNode { if (it is TextNode) texts += it }
        texts.forEach { node -> markIn(node, find(node.wholeText)) }
        return document.body().html()
    }

    private fun markIn(node: TextNode, ranges: List<IntRange>) {
        if (ranges.isEmpty()) return
        val text = node.wholeText
        var at = 0
        ranges.forEach { range ->
            if (range.first > at) node.before(TextNode(text.substring(at, range.first)))
            node.before(Element("mark").appendText(text.substring(range.first, range.last + 1)))
            at = range.last + 1
        }
        if (at < text.length) node.before(TextNode(text.substring(at)))
        node.remove()
    }
}
