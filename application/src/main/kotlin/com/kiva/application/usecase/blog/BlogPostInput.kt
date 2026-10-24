package com.kiva.application.usecase.blog

import com.kiva.application.usecase.product.MomentField
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.domain.aggregate.blog.model.BlogPostDraft
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostId
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer

/** The form of `POST`/`PUT /admin/blog/posts*` checked before any transaction: every problem at once (400). */
fun draftOf(fields: BlogPostDraft.Fields, publishedAt: String?, sanitizer: HtmlSanitizer) =
    BlogPostDraft.makeNew(fields, MomentField.makeNew(publishedAt), sanitizer)

/** What a post's save failed on; shared by creating and updating. */
sealed class SaveBlogPostFailure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
    data class InvalidInput(val problems: List<ResultFailure>) : SaveBlogPostFailure("VALIDATION_ERROR", FailureKind.INVALID, problems)

    data object NotFound : SaveBlogPostFailure("NOT_FOUND", FailureKind.NOT_FOUND)

    data object DuplicateSlug : SaveBlogPostFailure("DUPLICATE_SLUG", FailureKind.CONFLICT)
}

/** A path's post number; anything that can't be one names no post (404, as one nobody has). */
fun postIdOf(raw: String): BlogPostId? = raw.trim().normalizeDigits().toLongOrNull()?.let { BlogPostId.makeNew(it).getOrNull() }
