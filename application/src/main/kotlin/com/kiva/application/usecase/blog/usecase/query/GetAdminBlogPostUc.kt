package com.kiva.application.usecase.blog.usecase.query

import com.kiva.application.dto.AdminBlogPostDto
import com.kiva.application.usecase.blog.BlogPosts
import com.kiva.application.usecase.blog.postIdOf
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import java.time.Clock

/**
 * One post for the panel's form (`GET /admin/blog/posts/{postId}`; ADMIN, CONTENT_EDITOR; roadmap phase 15, decision 2):
 * every block (a product no longer in the shop too, named in `unavailableProductIds`), its status and schedule, and the
 * last «no» comments. 404 for a post nobody has or a path that can't be one. Also the answer of a create or an update.
 */
class GetAdminBlogPostUc(
    private val transactionManager: TransactionManager,
    private val blogPostRepository: BlogPostRepository,
    private val blogPosts: BlogPosts,
    private val clock: Clock,
) : QueryUseCase<GetAdminBlogPostUc.Qry, AdminBlogPostDto, GetAdminBlogPostUc.Failure> {

    private val logger = getLogger()

    data class Qry(val postId: String)

    override suspend fun execute(query: Qry): Result<AdminBlogPostDto, Failure> {
        logger.info("GetAdminBlogPostUc.execute post=${query.postId.take(20)}")
        val id = postIdOf(query.postId) ?: return Result.failure(Failure.NotFound)
        return transactionManager.inTransaction {
            val post = blogPostRepository.find(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(blogPosts.adminOf(post, clock.instant()))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
