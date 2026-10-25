package com.kiva.application.usecase.blog.usecase.command

import com.kiva.application.port.VoterHashes
import com.kiva.application.usecase.blog.postIdOf
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.blog.model.BlogVote
import com.kiva.domain.aggregate.blog.repository.BlogFeedbackRepository
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import java.time.Clock

/**
 * «این مطلب مفید بود؟» (`POST /blog/posts/{postId}/feedback`; a login optional; roadmap phase 15, decision 14). One vote
 * per post and voter, a vote again replacing the first: a customer votes as `u:{id}`, a guest as `ip:` and the keyed hash
 * of the address (VoterHashes), the address itself never stored. The post must be one the shop sees (404 otherwise).
 */
class SendBlogFeedbackUc(
    private val transactionManager: TransactionManager,
    private val blogPostRepository: BlogPostRepository,
    private val blogFeedbackRepository: BlogFeedbackRepository,
    private val voterHashes: VoterHashes,
    private val clock: Clock,
) : CommandUseCase<SendBlogFeedbackUc.Cmd, Unit, SendBlogFeedbackUc.Failure> {

    private val logger = getLogger()

    /** [voter] the logged-in customer, or null for a guest known only by [ip]. */
    data class Cmd(val postId: String, val helpful: Boolean?, val comment: String?, val voter: UserId?, val ip: String) {
        override fun toString(): String = "Cmd(postId=$postId, helpful=$helpful, voter=${voter?.value})"
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("SendBlogFeedbackUc.execute post=${command.postId.take(20)} helpful=${command.helpful} customer=${command.voter != null}")
        val vote = BlogVote.makeNew(command.helpful, command.comment).unwrapOr { return Result.failure(Failure.InvalidInput(it)) }
        val id = postIdOf(command.postId) ?: return Result.failure(Failure.NotFound)
        val voterKey = command.voter?.let { "u:${it.value}" } ?: "ip:${voterHashes.hashOf(command.ip)}"
        return transactionManager.inTransaction {
            val now = clock.instant()
            val post = blogPostRepository.find(id)?.takeIf { it.isVisibleAt(now) } ?: return@inTransaction Result.failure(Failure.NotFound)
            blogFeedbackRepository.upsert(post.id, voterKey, vote.helpful, vote.comment, now)
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: InputProblems) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems.causes)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
