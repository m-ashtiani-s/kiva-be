package com.kiva.application.usecase.newsletter.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.newsletter.model.valueobject.UnsubscribeToken
import com.kiva.domain.aggregate.newsletter.repository.NewsletterSubscriptionRepository
import java.time.Clock

/**
 * The unsubscribe link of a welcome mail (`POST /newsletter/subscriptions/unsubscribe`, public; roadmap phase 13,
 * decision 15). A token not of its shape is 400 UNSUBSCRIBE_TOKEN_INVALID; one nobody has, or already unsubscribed,
 * changes nothing; else UNSUBSCRIBED from now. All three answer the same: the answer doesn't say whether it was there.
 */
class UnsubscribeNewsletterUc(
    private val transactionManager: TransactionManager,
    private val subscriptionRepository: NewsletterSubscriptionRepository,
    private val clock: Clock,
) : CommandUseCase<UnsubscribeNewsletterUc.Cmd, Unit, UnsubscribeNewsletterUc.Failure> {

    private val logger = getLogger()

    /** The token is a secret: [toString] hides it. */
    data class Cmd(val token: String?) {
        override fun toString(): String = "Cmd(***)"
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val raw = command.token?.trim()?.ifEmpty { null } ?: return Result.failure(Failure.InvalidInput(FieldFailure("token", FieldRequired)))
        val token = UnsubscribeToken.of(raw) ?: return Result.failure(Failure.InvalidInput(FieldFailure("token", Failure.TokenInvalid)))

        return transactionManager.inTransaction {
            val subscription = subscriptionRepository.lockFindByToken(token)
            val unsubscribed = subscription?.unsubscribed(clock.instant())
            if (unsubscribed != null) subscriptionRepository.save(unsubscribed)
            logger.info("UnsubscribeNewsletterUc: ${if (unsubscribed != null) "unsubscribed" else "nothing to change"}")
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problem: ResultFailure) : Failure("VALIDATION_ERROR", FailureKind.INVALID, listOf(problem))

        data object TokenInvalid : ResultFailure("UNSUBSCRIBE_TOKEN_INVALID", FailureKind.INVALID)
    }
}
