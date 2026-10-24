package com.kiva.application.usecase.blog.usecase.query

import com.kiva.application.dto.BlogPostListDto
import com.kiva.application.usecase.blog.BlogPosts
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.blog.model.valueobject.BlogTag
import com.kiva.domain.aggregate.blog.repository.BlogCategoryRepository
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import java.time.Clock

/**
 * The magazine's list (`GET /blog/posts`; public; roadmap phase 15, decision 10): the posts the shop sees now, the latest
 * first. `category` a slug (an unknown one: an empty list, not an error), `q` words each in the post's search text (400
 * SEARCH_QUERY_TOO_LONG past 100), `tag` one of its tags, both normalized (a tag no post can have: an empty list).
 *
 * The featured post, when it fits the filter, is left out of every page's items and of the count, so the pages neither
 * repeat nor skip a post; it is given as `featured` on page one only.
 */
class ListBlogPostsUc(
    private val transactionManager: TransactionManager,
    private val blogPostRepository: BlogPostRepository,
    private val blogCategoryRepository: BlogCategoryRepository,
    private val blogPosts: BlogPosts,
    private val clock: Clock,
) : QueryUseCase<ListBlogPostsUc.Qry, BlogPostListDto, ListBlogPostsUc.Failure> {

    private val logger = getLogger()

    data class Qry(val category: String?, val q: String?, val tag: String?, val pageable: Pageable)

    override suspend fun execute(query: Qry): Result<BlogPostListDto, Failure> {
        logger.info("ListBlogPostsUc.execute category=${query.category?.take(60)} page=${query.pageable.page}")
        val words = BlogSearch.wordsOf(query.q).getOrNull() ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("q", BlogSearch.TooLong))))
        val none = BlogPostListDto(null, Page(emptyList(), query.pageable.page, query.pageable.size, 0))
        val tag = query.tag?.trim()?.ifEmpty { null }?.let { BlogTag.makeNew(it).getOrNull() ?: return Result.success(none) }

        return transactionManager.inTransaction {
            val category = query.category?.trim()?.ifEmpty { null }?.let { raw ->
                Slug.makeNew(raw).getOrNull()?.let(blogCategoryRepository::findBySlug) ?: return@inTransaction Result.success(none)
            }
            val now = clock.instant()
            val filter = BlogPostRepository.PublicFilter(category?.id, words, tag)
            val featured = blogPostRepository.featured(filter, now)
            val page = blogPostRepository.pagePublic(filter.copy(exclude = featured?.id), now, query.pageable)
            val shown = listOfNotNull(featured?.takeIf { query.pageable.page == 1 })
            val summaries = blogPosts.summariesOf(shown + page.items, now)
            Result.success(BlogPostListDto(summaries.firstOrNull()?.takeIf { shown.isNotEmpty() }, Page(summaries.drop(shown.size), page.page, page.size, page.totalItems)))
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
