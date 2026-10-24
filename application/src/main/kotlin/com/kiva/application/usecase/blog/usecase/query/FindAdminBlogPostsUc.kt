package com.kiva.application.usecase.blog.usecase.query

import com.kiva.application.dto.AdminBlogPostDto
import com.kiva.application.usecase.blog.BlogPosts
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersian
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostStatus
import com.kiva.domain.aggregate.blog.repository.BlogCategoryRepository
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import java.time.Clock

/**
 * The panel's list of posts (`GET /admin/blog/posts`; ADMIN, CONTENT_EDITOR; roadmap phase 15, decision 2): every
 * status, the latest change first, without their blocks. `status` one of three (400 BLOG_POST_STATUS_INVALID), `q` the
 * same words as the shop's search (400 SEARCH_QUERY_TOO_LONG past 100), `category` a slug (an unknown one: none).
 */
class FindAdminBlogPostsUc(
    private val transactionManager: TransactionManager,
    private val blogPostRepository: BlogPostRepository,
    private val blogCategoryRepository: BlogCategoryRepository,
    private val blogPosts: BlogPosts,
    private val clock: Clock,
) : QueryUseCase<FindAdminBlogPostsUc.Qry, Page<AdminBlogPostDto>, FindAdminBlogPostsUc.Failure> {

    private val logger = getLogger()

    data class Qry(val status: String?, val q: String?, val category: String?, val pageable: Pageable)

    override suspend fun execute(query: Qry): Result<Page<AdminBlogPostDto>, Failure> {
        logger.info("FindAdminBlogPostsUc.execute status=${query.status?.take(20)} category=${query.category?.take(60)} page=${query.pageable.page}")
        val status = query.status?.trim()?.ifEmpty { null }?.let {
            BlogPostStatus.makeNew(it).getOrNull() ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("status", BlogPostStatus.Invalid))))
        }
        val words = BlogSearch.wordsOf(query.q).getOrNull() ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("q", BlogSearch.TooLong))))

        return transactionManager.inTransaction {
            val category = query.category?.trim()?.ifEmpty { null }?.let { raw ->
                Slug.makeNew(raw).getOrNull()?.let(blogCategoryRepository::findBySlug) ?: return@inTransaction Result.success(Page(emptyList(), query.pageable.page, query.pageable.size, 0))
            }
            val page = blogPostRepository.pageAdmin(BlogPostRepository.AdminFilter(status, words, category?.id), query.pageable)
            Result.success(Page(blogPosts.adminRowsOf(page.items, clock.instant()), page.page, page.size, page.totalItems))
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}

/** The words of a blog search (decision 10): `q` normalized and split, at most [MAX_LENGTH] characters. */
object BlogSearch {
    const val MAX_LENGTH = 100

    fun wordsOf(q: String?): Result<List<String>, ResultFailure> {
        val text = q?.trim().orEmpty()
        if (text.length > MAX_LENGTH) return Result.failure(TooLong)
        return Result.success(text.normalizePersian().split(' ').filter { it.isNotBlank() })
    }

    data object TooLong : ResultFailure("SEARCH_QUERY_TOO_LONG", FailureKind.INVALID, meta = mapOf("maxLength" to MAX_LENGTH))
}
