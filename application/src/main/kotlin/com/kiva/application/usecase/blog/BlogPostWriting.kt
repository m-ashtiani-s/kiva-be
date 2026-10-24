package com.kiva.application.usecase.blog

import com.kiva.application.port.AuditChange
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.blog.entity.BlogPost
import com.kiva.domain.aggregate.blog.model.BlogPostDraft
import com.kiva.domain.aggregate.blog.model.valueobject.BlogAuthorId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlock
import com.kiva.domain.aggregate.blog.repository.BlogAuthorRepository
import com.kiva.domain.aggregate.blog.repository.BlogCategoryRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType

/**
 * What creating and updating a post check and record the same way (Rule 15; roadmap section 20.6), inside the caller's
 * transaction: the category and the author are there (400 BLOG_CATEGORY_NOT_FOUND / BLOG_AUTHOR_NOT_FOUND; no author
 * named is the default one), the cover and the photos are the blog's photos (400 MEDIA_*), every product of the blocks
 * is in the shop (400 PRODUCT_NOT_FOUND on `blocks.{i}.productId`). Every problem at once.
 */
class BlogPostWriting(
    private val blogCategoryRepository: BlogCategoryRepository,
    private val blogAuthorRepository: BlogAuthorRepository,
    private val mediaAttachments: MediaAttachments,
    private val productQmRepository: ProductQmRepository,
) {
    /** What the checks found: the post's author, and its category's name for the search text. */
    data class Checked(val authorId: BlogAuthorId, val categoryName: String)

    fun check(draft: BlogPostDraft): Result<Checked, InputProblems> {
        val problems = mutableListOf<ResultFailure>()
        val category = blogCategoryRepository.find(draft.categoryId)
        if (category == null) problems += FieldFailure("categoryId", BlogPostDraft.Failure.CategoryNotFound)
        val author = if (draft.authorId == null) blogAuthorRepository.findDefault() ?: error("No default blog author: V40 makes one") else blogAuthorRepository.find(draft.authorId!!)
        if (author == null) problems += FieldFailure("authorId", BlogPostDraft.Failure.AuthorNotFound)

        val wanted = listOfNotNull(draft.coverMediaId?.let { MediaAttachments.Wanted(it, "coverMediaId") }) +
            draft.blocks.mapIndexedNotNull { i, block -> (block as? BlogBlock.Image)?.let { MediaAttachments.Wanted(it.mediaId, "blocks.$i.imageMediaId") } }
        mediaAttachments.check(wanted, MediaPurpose.BLOG, setOf(MediaType.IMAGE)).failureOrNull()?.let { problems += it.causes }

        val named = draft.blocks.mapIndexedNotNull { i, block -> (block as? BlogBlock.ProductCard)?.let { i to it.productId } }
        if (named.isNotEmpty()) {
            val inShop = productQmRepository.findCards(named.map { it.second }.distinct()).map { it.productId }.toSet()
            named.filterNot { it.second in inShop }.forEach { (i, _) -> problems += FieldFailure("blocks.$i.productId", ProductNotFound) }
        }
        return if (problems.isEmpty()) Result.success(Checked(author!!.id, category!!.name)) else Result.failure(InputProblems(problems))
    }

    /** A block's product that isn't in the shop (a draft, archived, or nobody's). */
    data object ProductNotFound : ResultFailure("PRODUCT_NOT_FOUND", FailureKind.INVALID)

    companion object {
        /**
         * The change log's `changes` of a post (decision 17): every field as it was and is; the blocks only as «changed»
         * and how many.
         */
        fun changesOf(before: BlogPost?, after: BlogPost): List<AuditChange> = AuditDiff.build {
            field("title", before?.title, after.title)
            field("slug", before?.slug?.value, after.slug.value)
            field("status", before?.status?.name, after.status.name)
            field("publishedAt", before?.publishedAt?.toString(), after.publishedAt?.toString())
            field("isFeatured", before?.isFeatured, after.isFeatured)
            field("categoryId", before?.categoryId?.value, after.categoryId.value)
            field("authorId", before?.authorId?.value, after.authorId.value)
            field("coverMediaId", before?.coverMediaId?.value, after.coverMediaId?.value)
            field("readingMinutes", before?.readingMinutes, after.readingMinutes)
            list("tags", before?.tags?.map { it.value }, after.tags.map { it.value })
            longText("excerpt", before?.excerpt, after.excerpt)
            longText("lead", before?.lead, after.lead)
            longText("blocks", before?.blocks?.toString(), after.blocks.toString())
            field("blocksCount", before?.blocks?.size, after.blocks.size)
            json("seo", before?.seo?.let { mapOf("title" to it.title, "description" to it.description, "noIndex" to it.noIndex) }, mapOf("title" to after.seo.title, "description" to after.seo.description, "noIndex" to after.seo.noIndex))
        }
    }
}
