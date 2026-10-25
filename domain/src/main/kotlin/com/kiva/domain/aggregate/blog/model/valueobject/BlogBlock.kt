package com.kiva.domain.aggregate.blog.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.TooManyItems
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.forField
import com.kiva.common.text.normalizePersianLetters
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import com.kiva.domain.aggregate.product.model.valueobject.ProductId

/** A paragraph's or a tip's words: plain text, or sanitized HTML (exactly one of them, decision 7). */
sealed interface TextOrHtml {
    @JvmInline
    value class Text(val value: String) : TextOrHtml

    @JvmInline
    value class Html(val value: String) : TextOrHtml
}

/**
 * One block of a post's body (roadmap phase 15, decision 7), each kind with only its own fields: what the form sends of
 * another kind's is not kept. Made by [BlogBlocks.makeNew].
 */
sealed interface BlogBlock {
    /** A heading; [anchor] is `s0`, `s1`, … in the order of the headings, for the table of contents (decision 8). */
    data class Heading(val anchor: String, val text: String) : BlogBlock

    data class Paragraph(val content: TextOrHtml) : BlogBlock

    data class Quote(val text: String) : BlogBlock

    /** «نکته کیوا»: [title] null means the default title (the HTTP layer's). */
    data class Tip(val title: String?, val content: TextOrHtml) : BlogBlock

    /** A product card inside the text; one not in the shop any more is left out of what the shop shows (decision 9). */
    data class ProductCard(val productId: ProductId) : BlogBlock

    data class Image(val mediaId: MediaId, val caption: String?) : BlogBlock

    data class ListBlock(val items: List<String>) : BlogBlock
}

/** The kinds of [BlogBlock], by the contract's names. */
enum class BlogBlockType { HEADING, PARAGRAPH, QUOTE, TIP, PRODUCT, IMAGE, LIST }

/**
 * Checks a post's blocks as the form sends them (`blocks`, decision 7): 1 to [MAX_BLOCKS], each by its kind, every
 * problem at once under `blocks.{i}.{field}`. Whether a product is in the shop and a photo is the blog's is the use
 * case's (they need the read model).
 */
object BlogBlocks {
    const val MAX_BLOCKS = 100
    const val MAX_HEADING = 120
    const val MAX_TEXT = 2000
    const val MAX_HTML = 5000
    const val MAX_QUOTE = 500
    const val MAX_TIP_TITLE = 60
    const val MAX_CAPTION = 200
    const val MAX_LIST_ITEMS = 20
    const val MAX_LIST_ITEM = 300

    /** A block as the form sends it: every field of every kind, as it came. */
    data class Raw(
        val type: String?,
        val text: String?,
        val html: String?,
        val title: String?,
        val items: List<String?>?,
        val productId: Long?,
        val imageMediaId: String?,
    )

    fun makeNew(raw: List<Raw?>?, sanitizer: HtmlSanitizer): Result<List<BlogBlock>, InputProblems> {
        if (raw.isNullOrEmpty()) return Result.failure(InputProblems(listOf(FieldFailure("blocks", FieldRequired))))
        if (raw.size > MAX_BLOCKS) return Result.failure(InputProblems(listOf(FieldFailure("blocks", TooManyItems(MAX_BLOCKS)))))
        val problems = mutableListOf<ResultFailure>()
        var headings = 0
        val blocks = raw.mapIndexedNotNull { i, block ->
            val at = "blocks.$i"
            if (block == null) {
                problems += FieldFailure(at, FieldRequired)
                return@mapIndexedNotNull null
            }
            val type = when (val t = block.type) {
                null -> null.also { problems += FieldFailure("$at.type", FieldRequired) }
                else -> BlogBlockType.entries.firstOrNull { it.name == t } ?: null.also { problems += FieldFailure("$at.type", Failure.TypeInvalid) }
            } ?: return@mapIndexedNotNull null
            val made = blockOf(type, block, at, sanitizer, anchor = "s$headings")
            if (made is Made.Fine && made.block is BlogBlock.Heading) headings++
            when (made) {
                is Made.Fine -> made.block
                is Made.Refused -> null.also { problems += made.problems }
            }
        }
        return if (problems.isEmpty()) Result.success(blocks) else Result.failure(InputProblems(problems))
    }

    private sealed interface Made {
        class Fine(val block: BlogBlock) : Made
        class Refused(val problems: List<ResultFailure>) : Made
    }

    private fun blockOf(type: BlogBlockType, raw: Raw, at: String, sanitizer: HtmlSanitizer, anchor: String): Made {
        val problems = mutableListOf<ResultFailure>()
        fun <T> Result<T, ResultFailure>.at(field: String): T? = when (this) {
            is Result.Success -> value
            is Result.Failure -> null.also { problems += FieldFailure("$at.$field", failure) }
        }
        val block: BlogBlock? = when (type) {
            BlogBlockType.HEADING -> requiredText(raw.text, MAX_HEADING).at("text")?.let { BlogBlock.Heading(anchor, it) }
            BlogBlockType.PARAGRAPH -> contentOf(raw, sanitizer, at, problems)?.let(BlogBlock::Paragraph)
            BlogBlockType.QUOTE -> requiredText(raw.text, MAX_QUOTE).at("text")?.let(BlogBlock::Quote)
            BlogBlockType.TIP -> {
                val title = optionalText(raw.title, MAX_TIP_TITLE).at("title")
                contentOf(raw, sanitizer, at, problems)?.let { BlogBlock.Tip(title, it) }
            }
            BlogBlockType.PRODUCT -> when (val id = raw.productId) {
                null -> null.also { problems += FieldFailure("$at.productId", FieldRequired) }
                else -> ProductId.makeNew(id).at("productId")?.let(BlogBlock::ProductCard)
            }
            BlogBlockType.IMAGE -> {
                val caption = optionalText(raw.text, MAX_CAPTION).at("text")
                when (val id = raw.imageMediaId) {
                    null -> null.also { problems += FieldFailure("$at.imageMediaId", FieldRequired) }
                    else -> MediaId.makeNew(id).at("imageMediaId")?.let { BlogBlock.Image(it, caption) }
                }
            }
            BlogBlockType.LIST -> itemsOf(raw.items, at, problems)?.let(BlogBlock::ListBlock)
        }
        return if (problems.isEmpty() && block != null) Made.Fine(block) else Made.Refused(problems)
    }

    /** Exactly one of `text` (plain) or `html` (sanitized first, then measured). */
    private fun contentOf(raw: Raw, sanitizer: HtmlSanitizer, at: String, problems: MutableList<ResultFailure>): TextOrHtml? {
        val text = raw.text?.normalizePersianLetters()?.ifEmpty { null }
        val html = raw.html?.let(sanitizer::clean)?.trim()?.ifEmpty { null }
        return when {
            text != null && html != null -> null.also { problems += FieldFailure("$at.html", Failure.TextAndHtml) }
            text != null -> if (text.length > MAX_TEXT) null.also { problems += FieldFailure("$at.text", TextTooLong(MAX_TEXT)) } else TextOrHtml.Text(text)
            html != null -> if (html.length > MAX_HTML) null.also { problems += FieldFailure("$at.html", TextTooLong(MAX_HTML)) } else TextOrHtml.Html(html)
            else -> null.also { problems += FieldFailure("$at.text", FieldRequired) }
        }
    }

    private fun itemsOf(raw: List<String?>?, at: String, problems: MutableList<ResultFailure>): List<String>? {
        if (raw.isNullOrEmpty()) return null.also { problems += FieldFailure("$at.items", FieldRequired) }
        if (raw.size > MAX_LIST_ITEMS) return null.also { problems += FieldFailure("$at.items", TooManyItems(MAX_LIST_ITEMS)) }
        val before = problems.size
        val items = raw.mapIndexed { j, item ->
            when (val checked = requiredText(item, MAX_LIST_ITEM)) {
                is Result.Success -> checked.value
                is Result.Failure -> "".also { problems += FieldFailure("$at.items.$j", checked.failure) }
            }
        }
        return items.takeIf { problems.size == before }
    }

    private fun requiredText(raw: String?, max: Int): Result<String, ResultFailure> {
        val clean = raw?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
        return if (clean.length > max) Result.failure(TextTooLong(max)) else Result.success(clean)
    }

    private fun optionalText(raw: String?, max: Int): Result<String?, ResultFailure> {
        val clean = raw?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.success(null)
        return if (clean.length > max) Result.failure(TextTooLong(max)) else Result.success(clean)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        /** Not one of the seven kinds. */
        data object TypeInvalid : Failure("BLOG_BLOCK_TYPE_INVALID")

        /** A paragraph or a tip with both `text` and `html`: one of them. */
        data object TextAndHtml : Failure("BLOG_BLOCK_TEXT_OR_HTML")
    }
}
