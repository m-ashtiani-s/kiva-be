package com.kiva.application.usecase.newsletter.usecase.command

import com.kiva.application.port.EmailSender
import com.kiva.application.usecase.contact.EmailTexts
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.newsletter.entity.NewsletterSubscription
import com.kiva.domain.aggregate.newsletter.model.valueobject.SubscriptionSource
import com.kiva.domain.aggregate.newsletter.model.valueobject.UnsubscribeToken
import com.kiva.domain.aggregate.newsletter.repository.NewsletterSubscriptionRepository
import com.kiva.domain.aggregate.user.model.valueobject.Email
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Someone subscribes to the newsletter from the footer (`POST /newsletter/subscriptions`, public; roadmap section 18.6,
 * decisions 12 and 13). The email in lower case (400 when it isn't one); `source` kept only when of its shape. Then, in
 * one transaction, a new subscriber, or one unsubscribed made ACTIVE again, or one already ACTIVE left as it is: the
 * answer is the same for all three, and doesn't say which. After the commit, only a new or returning one gets the
 * welcome mail with its unsubscribe link; when it fails, only the log knows.
 *
 * The limit per address is the route's (RateLimit `newsletter`, decision 14). The log never has the email.
 */
class SubscribeNewsletterUc(
    private val transactionManager: TransactionManager,
    private val subscriptionRepository: NewsletterSubscriptionRepository,
    private val emailSender: EmailSender,
    private val siteUrl: String,
    private val clock: Clock,
    private val newToken: () -> UnsubscribeToken = { UnsubscribeToken.random() },
) : CommandUseCase<SubscribeNewsletterUc.Cmd, NewsletterSubscription.Outcome, SubscribeNewsletterUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; the email never goes into a log ([toString]). */
    data class Cmd(val email: String?, val source: String?) {
        override fun toString(): String = "Cmd(source=$source)"
    }

    override suspend fun execute(command: Cmd): Result<NewsletterSubscription.Outcome, Failure> {
        val email = command.email?.trim()?.ifEmpty { null }?.let { Email.makeNew(it).unwrapOr { failure -> return Result.failure(Failure.InvalidInput(listOf(failure))) } }
            ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("email", FieldRequired))))
        val source = SubscriptionSource.of(command.source)
        logger.info("SubscribeNewsletterUc.execute source=$source")

        val (subscription, outcome) = transactionManager.inTransaction {
            val now = clock.instant()
            repeat(MAX_TRIES) {
                val fresh = NewsletterSubscription.create(email, source, newToken(), now)
                if (subscriptionRepository.addIfAbsent(fresh)) return@inTransaction Result.success(fresh to NewsletterSubscription.Outcome.NEW)
                // Not inserted: the email is there (or, by chance, the token was taken; then a new one).
                val existing = subscriptionRepository.lockFind(email) ?: return@repeat
                val (again, outcome) = existing.subscribedAgain(now)
                if (outcome == NewsletterSubscription.Outcome.REACTIVATED) subscriptionRepository.save(again)
                return@inTransaction Result.success(again to outcome)
            }
            error("No free unsubscribe token in $MAX_TRIES tries")
        }.unwrapOr { return Result.failure(it) }

        logger.info("SubscribeNewsletterUc: $outcome")
        if (outcome != NewsletterSubscription.Outcome.ALREADY_ACTIVE) welcome(subscription)
        return Result.success(outcome)
    }

    private suspend fun welcome(subscription: NewsletterSubscription) {
        val link = "${siteUrl.trimEnd('/')}/newsletter/unsubscribe?token=${subscription.token.value}"
        try {
            emailSender.send(subscription.email, EmailTexts.newsletterWelcomeSubject(), EmailTexts.newsletterWelcomeBody(link))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The subscription stands. Only the error's kind: a mail server's words can hold the address.
            logger.warn("SubscribeNewsletterUc: the welcome mail could not be sent (${e::class.simpleName})")
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        /** No email, or not one (400 on `email`). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    companion object {
        /** A token taken by chance is drawn again, at most this many times in all. */
        const val MAX_TRIES = 5
    }
}
