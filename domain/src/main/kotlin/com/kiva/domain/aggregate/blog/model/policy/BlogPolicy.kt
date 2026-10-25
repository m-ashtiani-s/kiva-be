package com.kiva.domain.aggregate.blog.model.policy

import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlock
import com.kiva.domain.aggregate.blog.model.valueobject.TextOrHtml
import kotlin.math.ceil

/**
 * The blog's numbers and the two things a post works out when the editor left them empty (roadmap phase 15, decision 4):
 * its summary (from the lead, or else the first paragraph) and its reading time (words of the lead and every block).
 */
object BlogPolicy {
    const val WORDS_PER_MINUTE = 200
    const val MAX_TAGS = 10
    const val EXCERPT_FALLBACK_LENGTH = 160

    private val TAG = Regex("<[^>]*>")
    private val SPACE = Regex("\\s+")

    /** The words a reader sees of [content]: sanitized HTML loses its tags. */
    fun plainOf(content: TextOrHtml): String = when (content) {
        is TextOrHtml.Text -> content.value
        is TextOrHtml.Html -> content.value.replace(TAG, " ").replace("&nbsp;", " ").replace(SPACE, " ").trim()
    }

    /** The first [EXCERPT_FALLBACK_LENGTH] characters of [lead], or else of the first paragraph; null when there is neither. */
    fun excerptFallback(lead: String?, blocks: List<BlogBlock>): String? {
        val source = lead ?: blocks.firstNotNullOfOrNull { (it as? BlogBlock.Paragraph)?.content?.let(::plainOf) } ?: return null
        return if (source.length <= EXCERPT_FALLBACK_LENGTH) source else source.take(EXCERPT_FALLBACK_LENGTH).trimEnd() + "…"
    }

    /** Minutes to read [lead] and [blocks] at [WORDS_PER_MINUTE], rounded up, at least 1. */
    fun readingMinutesOf(lead: String?, blocks: List<BlogBlock>): Int {
        val texts = listOfNotNull(lead) + blocks.flatMap(::textsOf)
        val words = texts.sumOf { text -> text.split(SPACE).count { it.isNotBlank() } }
        return maxOf(1, ceil(words.toDouble() / WORDS_PER_MINUTE).toInt())
    }

    private fun textsOf(block: BlogBlock): List<String> = when (block) {
        is BlogBlock.Heading -> listOf(block.text)
        is BlogBlock.Paragraph -> listOf(plainOf(block.content))
        is BlogBlock.Quote -> listOf(block.text)
        is BlogBlock.Tip -> listOfNotNull(block.title, plainOf(block.content))
        is BlogBlock.ProductCard -> emptyList()
        is BlogBlock.Image -> listOfNotNull(block.caption)
        is BlogBlock.ListBlock -> block.items
    }
}
