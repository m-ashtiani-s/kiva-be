package com.kiva.application.usecase.newsletter.usecase.query

import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.newsletter.entity.NewsletterSubscription
import com.kiva.domain.aggregate.newsletter.model.valueobject.SubscriptionStatus
import com.kiva.domain.aggregate.newsletter.repository.NewsletterSubscriptionRepository

/**
 * The panel's newsletter list (`GET /admin/newsletter/subscriptions`; ADMIN only, the emails are personal; roadmap
 * phase 13, decision 17): every subscriber, or one status; `q` a part of the email (lower case, at most 100); newest
 * subscribed first. An unknown status is 400 on `status`.
 */
class FindNewsletterSubscriptionsUc(
    private val transactionManager: TransactionManager,
    private val subscriptionRepository: NewsletterSubscriptionRepository,
) : QueryUseCase<FindNewsletterSubscriptionsUc.Qry, Page<NewsletterSubscription>, FindNewsletterSubscriptionsUc.Failure> {

    private val logger = getLogger()

    /** The query parameters as they came; [q] may be a part of an email: [toString] hides it. */
    data class Qry(val pageable: Pageable, val status: String? = null, val q: String? = null) {
        override fun toString(): String = "Qry(page=${pageable.page}, status=$status, q=${q != null})"
    }

    override suspend fun execute(query: Qry): Result<Page<NewsletterSubscription>, Failure> {
        logger.info("FindNewsletterSubscriptionsUc.execute status=${query.status} q=${query.q != null} page=${query.pageable.page}")
        val problems = mutableListOf<ResultFailure>()
        val status = query.status?.trim()?.ifEmpty { null }?.let { raw ->
            SubscriptionStatus.of(raw) ?: null.also { problems += FieldFailure("status", Failure.StatusInvalid) }
        }
        val q = query.q?.trim()?.lowercase()?.ifEmpty { null }
        if (q != null && q.length > MAX_Q) problems += FieldFailure("q", TextTooLong(MAX_Q))
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))

        return transactionManager.inTransaction {
            Result.success(subscriptionRepository.page(NewsletterSubscriptionRepository.Filter(status, q), query.pageable))
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object StatusInvalid : ResultFailure("SUBSCRIPTION_STATUS_INVALID", FailureKind.INVALID)
    }

    companion object {
        const val MAX_Q = 100
    }
}
