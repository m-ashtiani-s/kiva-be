package com.kiva.application.usecase.blog.usecase.query

import com.kiva.application.dto.BlogPostDetailDto
import com.kiva.application.usecase.blog.BlogPosts
import com.kiva.application.usecase.blog.postIdOf
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import java.time.Clock

/**
 * A post's page in the shop (`GET /blog/posts/{idOrSlug}`; public; roadmap phase 15, decision 12). Only digits: the id,
 * otherwise the slug (a slug always has a letter). A draft, an archived post, one scheduled for later and one nobody has
 * are all 404 NOT_FOUND, the same answer. A PRODUCT block whose product is not in the shop any more is left out.
 */
class GetBlogPostUc(
    private val transactionManager: TransactionManager,
    private val blogPostRepository: BlogPostRepository,
    private val blogPosts: BlogPosts,
    private val clock: Clock,
) : QueryUseCase<GetBlogPostUc.Qry, BlogPostDetailDto, GetBlogPostUc.Failure> {

    private val logger = getLogger()

    data class Qry(val idOrSlug: String)

    override suspend fun execute(query: Qry): Result<BlogPostDetailDto, Failure> {
        logger.info("GetBlogPostUc.execute post=${query.idOrSlug.take(100)}")
        val raw = query.idOrSlug.trim()
        return transactionManager.inTransaction {
            val post = if (raw.normalizeDigits().all { it in '0'..'9' }) {
                postIdOf(raw)?.let(blogPostRepository::find)
            } else {
                Slug.makeNew(raw).getOrNull()?.let(blogPostRepository::findBySlug)
            }
            val now = clock.instant()
            if (post == null || !post.isVisibleAt(now)) return@inTransaction Result.failure(Failure.NotFound)
            Result.success(blogPosts.pageOf(post, now))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
