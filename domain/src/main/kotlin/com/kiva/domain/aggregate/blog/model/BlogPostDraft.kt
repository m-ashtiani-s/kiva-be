package com.kiva.domain.aggregate.blog.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.TooManyItems
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.requiredIn
import com.kiva.common.text.normalizePersianLetters
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.blog.model.policy.BlogPolicy
import com.kiva.domain.aggregate.blog.model.valueobject.BlogAuthorId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlock
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlocks
import com.kiva.domain.aggregate.blog.model.valueobject.BlogCategoryId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostStatus
import com.kiva.domain.aggregate.blog.model.valueobject.BlogTag
import com.kiva.domain.aggregate.blog.model.valueobject.CoverBackground
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import java.time.Instant

/**
 * A post as the panel's form sends it (`POST`/`PUT /admin/blog/posts`; roadmap phase 15, decisions 3 to 8), checked:
 * every problem at once, each under its field (`title`, `blocks.3.productId`, `seo.title`). Whether the category,
 * the author, the photos and the products are there is the use case's: it needs the database.
 *
 * @property excerpt, lead, readingMinutes null when left empty: worked out when shown (BlogPolicy).
 * @property authorId null when not sent: the default author.
 * @property tags normalized, each once, in the order sent.
 * @property publishedAt as sent; when the post is published without one, BlogPost decides (decision 3).
 */
@ConsistentCopyVisibility
data class BlogPostDraft private constructor(
    val title: String,
    val slug: Slug,
    val excerpt: String?,
    val lead: String?,
    val categoryId: BlogCategoryId,
    val authorId: BlogAuthorId?,
    val coverMediaId: MediaId?,
    val coverBackground: CoverBackground?,
    val readingMinutes: Int?,
    val blocks: List<BlogBlock>,
    val tags: List<BlogTag>,
    val isFeatured: Boolean,
    val status: BlogPostStatus,
    val publishedAt: Instant?,
    val seo: SeoMeta,
) {
    override fun toString(): String = "BlogPostDraft(slug=${slug.value}, status=$status, blocks=${blocks.size})"

    /** The form's fields as they came. */
    data class Fields(
        val title: String?,
        val slug: String?,
        val excerpt: String?,
        val lead: String?,
        val categoryId: Long?,
        val authorId: Long?,
        val coverMediaId: String?,
        val coverBackground: String?,
        val readingMinutes: Int?,
        val blocks: List<BlogBlocks.Raw?>?,
        val tags: List<String?>?,
        val isFeatured: Boolean?,
        val status: String?,
        val seoTitle: String?,
        val seoDescription: String?,
        val seoNoIndex: Boolean?,
    ) {
        override fun toString(): String = "Fields(slug=$slug, status=$status, blocks=${blocks?.size})"
    }

    companion object {
        const val MAX_TITLE = 120
        const val MAX_EXCERPT = 300
        const val MAX_LEAD = 600
        val READING_MINUTES = 1..60

        /** [publishedAt] already read from the form (a malformed one is its failure, under `publishedAt`). */
        fun makeNew(fields: Fields, publishedAt: Result<Instant?, ResultFailure>, sanitizer: HtmlSanitizer): Result<BlogPostDraft, InputProblems> {
            val problems = Problems()
            val title = problems.take(requiredText(fields.title, MAX_TITLE).forField("title"))
            val slug = problems.take((fields.slug?.let { Slug.makeNew(it) } ?: Result.failure(FieldRequired)).forField("slug"))
            val excerpt = problems.take(optionalText(fields.excerpt, MAX_EXCERPT).forField("excerpt"))
            val lead = problems.take(optionalText(fields.lead, MAX_LEAD).forField("lead"))
            val categoryId = problems.take(categoryOf(fields.categoryId).forField("categoryId"))
            val authorId = problems.take(authorOf(fields.authorId).forField("authorId"))
            val cover = problems.take((fields.coverMediaId?.trim()?.ifEmpty { null }?.let { MediaId.makeNew(it) } ?: Result.success(null)).forField("coverMediaId"))
            val background = problems.take(
                (fields.coverBackground?.trim()?.ifEmpty { null }?.let { CoverBackground.makeNew(it) } ?: Result.success(null)).forField("coverBackground"),
            )
            val minutes = problems.take((fields.readingMinutes?.let { it.requiredIn(READING_MINUTES) } ?: Result.success(null)).forField("readingMinutes"))
            val blocks = problems.take(BlogBlocks.makeNew(fields.blocks, sanitizer))
            val tags = problems.take(tagsOf(fields.tags))
            val status = problems.take((fields.status?.let { BlogPostStatus.makeNew(it) } ?: Result.success(BlogPostStatus.DRAFT)).forField("status"))
            val moment = problems.take(publishedAt.forField("publishedAt"))
            val seo = problems.take(SeoMeta.makeNew(fields.seoTitle, fields.seoDescription, fields.seoNoIndex))
            if (problems.any()) return Result.failure(InputProblems(problems.all().causes.flattenGroups()))
            return Result.success(
                BlogPostDraft(
                    title.value, slug.value, excerpt.value, lead.value, categoryId.value, authorId.value, cover.value, background.value,
                    minutes.value, blocks.value, tags.value, fields.isFeatured ?: false, status.value, moment.value, seo.value,
                ),
            )
        }

        private fun requiredText(raw: String?, max: Int): Result<String, ResultFailure> {
            val clean = raw?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
            return if (clean.length > max) Result.failure(TextTooLong(max)) else Result.success(clean)
        }

        private fun optionalText(raw: String?, max: Int): Result<String?, ResultFailure> {
            val clean = raw?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.success(null)
            return if (clean.length > max) Result.failure(TextTooLong(max)) else Result.success(clean)
        }

        /** A number that can't be a category's is no category: the same answer as one nobody made. */
        private fun categoryOf(raw: Long?): Result<BlogCategoryId, ResultFailure> = when {
            raw == null -> Result.failure(FieldRequired)
            raw <= 0 -> Result.failure(Failure.CategoryNotFound)
            else -> Result.success(BlogCategoryId.constructFromPersisted(raw))
        }

        private fun authorOf(raw: Long?): Result<BlogAuthorId?, ResultFailure> = when {
            raw == null -> Result.success(null)
            raw <= 0 -> Result.failure(Failure.AuthorNotFound)
            else -> Result.success(BlogAuthorId.constructFromPersisted(raw))
        }

        /** At most [BlogPolicy.MAX_TAGS] after the repeats are dropped; each under `tags.{i}`. */
        private fun tagsOf(raw: List<String?>?): Result<List<BlogTag>, ResultFailure> {
            if (raw.isNullOrEmpty()) return Result.success(emptyList())
            val problems = mutableListOf<ResultFailure>()
            val tags = raw.mapIndexedNotNull { i, tag ->
                when (val made = BlogTag.makeNew(tag.orEmpty())) {
                    is Result.Success -> made.value
                    is Result.Failure -> null.also { problems += FieldFailure("tags.$i", made.failure) }
                }
            }.distinct()
            return when {
                problems.isNotEmpty() -> Result.failure(InputProblems(problems))
                tags.size > BlogPolicy.MAX_TAGS -> Result.failure(FieldFailure("tags", TooManyItems(BlogPolicy.MAX_TAGS)))
                else -> Result.success(tags)
            }
        }
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        /** A category nobody made (decision 1: they are made with SQL). */
        data object CategoryNotFound : Failure("BLOG_CATEGORY_NOT_FOUND")

        data object AuthorNotFound : Failure("BLOG_AUTHOR_NOT_FOUND")
    }
}
