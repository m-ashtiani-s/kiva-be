package com.kiva.application.usecase.blog.usecase.query

import com.kiva.application.dto.BlogPostSummaryDto
import com.kiva.application.usecase.blog.BlogPosts
import com.kiva.application.usecase.blog.postIdOf
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.NumberOutOfRange
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import java.time.Clock

/**
 * «مطالب مرتبط» under a post (`GET /blog/posts/{postId}/related`; public; roadmap phase 15, decision 13): those of its
 * category first, then the rest, each part the latest first, never the post itself. The post must be one the shop sees
 * (404). `limit` 1 to 12, 3 by default; anything else, a non-number too, is 400 NUMBER_OUT_OF_RANGE on `limit`.
 */
class ListRelatedBlogPostsUc(
    private val transactionManager: TransactionManager,
    private val blogPostRepository: BlogPostRepository,
    private val blogPosts: BlogPosts,
    private val clock: Clock,
) : QueryUseCase<ListRelatedBlogPostsUc.Qry, List<BlogPostSummaryDto>, ListRelatedBlogPostsUc.Failure> {

    private val logger = getLogger()

    data class Qry(val postId: String, val limit: String? = null)

    override suspend fun execute(query: Qry): Result<List<BlogPostSummaryDto>, Failure> {
        logger.info("ListRelatedBlogPostsUc.execute post=${query.postId.take(20)} limit=${query.limit?.take(10)}")
        val limit = limitOf(query.limit)
            ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("limit", NumberOutOfRange(1, MAX_LIMIT.toLong())))))
        val id = postIdOf(query.postId) ?: return Result.failure(Failure.NotFound)
        return transactionManager.inTransaction {
            val now = clock.instant()
            val post = blogPostRepository.find(id)?.takeIf { it.isVisibleAt(now) } ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(blogPosts.summariesOf(blogPostRepository.related(post, now, limit), now))
        }
    }

    /** Missing or empty is the default; a number outside 1..[MAX_LIMIT] or not a number is null. */
    private fun limitOf(raw: String?): Int? {
        val text = raw?.trim()?.ifEmpty { null } ?: return DEFAULT_LIMIT
        return text.normalizeDigits().toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
    }

    companion object {
        /** From the contract's `limit`. */
        const val DEFAULT_LIMIT = 3
        const val MAX_LIMIT = 12
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
