package com.kiva.domain.aggregate.blog.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.text.normalizePersian

/** A blog post's number: `GET /blog/posts/12`, `PUT /admin/blog/posts/12`. Given before the post is stored (`blogpostid_seq`). */
@JvmInline
value class BlogPostId private constructor(val value: Long) {
    init {
        require(value > 0) { "Invalid BlogPostId $value" }
    }

    companion object {
        /** A path's number; anything but a positive one names no post. */
        fun makeNew(raw: Long): Result<BlogPostId, NotOne> = if (raw > 0) Result.success(BlogPostId(raw)) else Result.failure(NotOne)

        fun constructFromPersisted(value: Long): BlogPostId = BlogPostId(value)
    }

    data object NotOne : ResultFailure("NOT_FOUND", FailureKind.NOT_FOUND)
}

/** A category of the blog («راهنمای خرید»), made with SQL only (roadmap phase 15, decision 1). */
@JvmInline
value class BlogCategoryId private constructor(val value: Long) {
    init {
        require(value > 0) { "Invalid BlogCategoryId $value" }
    }

    companion object {
        fun constructFromPersisted(value: Long): BlogCategoryId = BlogCategoryId(value)
    }
}

/** An author of the blog («تیم کیوا»), made with SQL only (decision 1). */
@JvmInline
value class BlogAuthorId private constructor(val value: Long) {
    init {
        require(value > 0) { "Invalid BlogAuthorId $value" }
    }

    companion object {
        fun constructFromPersisted(value: Long): BlogAuthorId = BlogAuthorId(value)
    }
}

/** Where a post is: written but not shown, shown (from its `publishedAt`), or put away but kept (decision 3). */
enum class BlogPostStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED,
    ;

    companion object {
        fun makeNew(raw: String): Result<BlogPostStatus, Invalid> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Invalid)
    }

    data object Invalid : ResultFailure("BLOG_POST_STATUS_INVALID", FailureKind.INVALID)
}

/**
 * A tag of a post («کیف دوشی»), 1 to [MAX_LENGTH] after normalizePersian, so «كيف» and «کیف» are one tag and `?tag=`
 * finds both (decision 4).
 */
@JvmInline
value class BlogTag private constructor(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid BlogTag of length ${value.length}" }
    }

    companion object {
        const val MAX_LENGTH = 30

        fun makeNew(raw: String): Result<BlogTag, ResultFailure> {
            val clean = raw.normalizePersian()
            return when {
                clean.isEmpty() -> Result.failure(Failure.Empty)
                clean.length > MAX_LENGTH -> Result.failure(TextTooLong(MAX_LENGTH))
                else -> Result.success(BlogTag(clean))
            }
        }

        fun constructFromPersisted(value: String): BlogTag = BlogTag(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Empty : Failure("BLOG_TAG_EMPTY")
    }
}

/** The colour behind an illustrated cover when there is no cover photo (`#E4D9F3`). */
@JvmInline
value class CoverBackground private constructor(val value: String) {
    init {
        require(FORMAT.matches(value)) { "Invalid CoverBackground '$value'" }
    }

    companion object {
        private val FORMAT = Regex("#[0-9A-Fa-f]{6}")

        fun makeNew(raw: String): Result<CoverBackground, Invalid> =
            if (FORMAT.matches(raw.trim())) Result.success(CoverBackground(raw.trim())) else Result.failure(Invalid)

        fun constructFromPersisted(value: String): CoverBackground = CoverBackground(value)
    }

    data object Invalid : ResultFailure("HEX_COLOR_INVALID", FailureKind.INVALID)
}
