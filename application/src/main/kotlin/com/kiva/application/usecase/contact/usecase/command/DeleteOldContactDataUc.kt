package com.kiva.application.usecase.contact.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.contact.model.policy.ContactPolicy
import com.kiva.domain.aggregate.contact.repository.ContactMessageRepository
import com.kiva.domain.aggregate.newsletter.repository.NewsletterSubscriptionRepository
import java.time.Clock

/**
 * The nightly clean-up `delete-old-contact-data` (roadmap phase 13, decision 19): personal data kept no longer than it
 * is needed. CLOSED contact messages last changed more than 365 days ago, and subscribers UNSUBSCRIBED more than 90
 * days ago, are deleted; an OPEN or IN_PROGRESS message, or an ACTIVE subscriber, stays however old.
 */
class DeleteOldContactDataUc(
    private val transactionManager: TransactionManager,
    private val contactMessageRepository: ContactMessageRepository,
    private val subscriptionRepository: NewsletterSubscriptionRepository,
    private val clock: Clock,
) : CommandUseCase<DeleteOldContactDataUc.Cmd, DeleteOldContactDataUc.Deleted, Nothing> {

    private val logger = getLogger()

    data object Cmd

    data class Deleted(val messages: Int, val subscriptions: Int)

    override suspend fun execute(command: Cmd): Result<Deleted, Nothing> {
        val now = clock.instant()
        val deleted = transactionManager.inTransaction {
            Result.success(
                Deleted(
                    messages = contactMessageRepository.deleteClosedBefore(now.minus(ContactPolicy.KEEP_CLOSED)),
                    subscriptions = subscriptionRepository.deleteUnsubscribedBefore(now.minus(ContactPolicy.KEEP_UNSUBSCRIBED)),
                ),
            )
        }.getOrNull() ?: Deleted(0, 0)
        logger.info("DeleteOldContactDataUc: ${deleted.messages} closed contact messages and ${deleted.subscriptions} unsubscribed subscribers deleted")
        return Result.success(deleted)
    }
}
