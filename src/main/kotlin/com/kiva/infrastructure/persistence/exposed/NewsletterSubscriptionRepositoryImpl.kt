package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.domain.aggregate.newsletter.entity.NewsletterSubscription
import com.kiva.domain.aggregate.newsletter.model.valueobject.SubscriptionStatus
import com.kiva.domain.aggregate.newsletter.model.valueobject.UnsubscribeToken
import com.kiva.domain.aggregate.newsletter.repository.NewsletterSubscriptionRepository
import com.kiva.domain.aggregate.user.model.valueobject.Email
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.persistence.exposed.tbl.NewsletterSubscriptionTbl
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class NewsletterSubscriptionRepositoryImpl : NewsletterSubscriptionRepository {

    override fun addIfAbsent(subscription: NewsletterSubscription): Boolean =
        NewsletterSubscriptionTbl.insertIgnore {
            it[email] = subscription.email.value
            it[status] = subscription.status
            it[sourceName] = subscription.source
            it[token] = subscription.token.value
            it[subscribedAt] = subscription.subscribedAt.utc()
            it[unsubscribedAt] = subscription.unsubscribedAt?.utc()
        }.insertedCount == 1

    override fun lockFind(email: Email): NewsletterSubscription? =
        NewsletterSubscriptionTbl.selectAll().where { NewsletterSubscriptionTbl.email eq email.value }.forUpdate().singleOrNull()?.toSubscription()

    override fun lockFindByToken(token: UnsubscribeToken): NewsletterSubscription? =
        NewsletterSubscriptionTbl.selectAll().where { NewsletterSubscriptionTbl.token eq token.value }.forUpdate().singleOrNull()?.toSubscription()

    override fun save(subscription: NewsletterSubscription) {
        val id = checkNotNull(subscription.id) { "Only a stored subscription can be saved" }
        NewsletterSubscriptionTbl.update({ NewsletterSubscriptionTbl.id eq id }) {
            it[status] = subscription.status
            it[subscribedAt] = subscription.subscribedAt.utc()
            it[unsubscribedAt] = subscription.unsubscribedAt?.utc()
        }
    }

    override fun page(filter: NewsletterSubscriptionRepository.Filter, pageable: Pageable): Page<NewsletterSubscription> {
        val conditions = listOfNotNull(
            filter.status?.let { NewsletterSubscriptionTbl.status eq it },
            filter.q?.let { NewsletterSubscriptionTbl.email like containing(it) },
        )
        val where: Op<Boolean> = conditions.fold(Op.TRUE as Op<Boolean>) { all, one -> all and one }
        return NewsletterSubscriptionTbl.selectAll().where { where }
            .paginate(pageable, NEWEST_FIRST, tiebreaker = NewsletterSubscriptionTbl.id, tiebreakerOrder = SortOrder.DESC) { it.toSubscription() }
    }

    override fun deleteUnsubscribedBefore(before: Instant): Int =
        NewsletterSubscriptionTbl.deleteWhere {
            (NewsletterSubscriptionTbl.status eq SubscriptionStatus.UNSUBSCRIBED) and (NewsletterSubscriptionTbl.unsubscribedAt less before.utc())
        }

    private fun ResultRow.toSubscription() = NewsletterSubscription.constructFromPersisted(
        id = this[NewsletterSubscriptionTbl.id].value,
        email = Email.constructFromPersisted(this[NewsletterSubscriptionTbl.email]),
        status = this[NewsletterSubscriptionTbl.status],
        source = this[NewsletterSubscriptionTbl.sourceName],
        token = UnsubscribeToken.constructFromPersisted(this[NewsletterSubscriptionTbl.token]),
        subscribedAt = this[NewsletterSubscriptionTbl.subscribedAt].toInstant(),
        unsubscribedAt = this[NewsletterSubscriptionTbl.unsubscribedAt]?.toInstant(),
    )

    private fun containing(text: String): LikePattern = LikePattern("%", ESCAPE) + LikePattern.ofLiteral(text, ESCAPE) + "%"

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private companion object {
        const val ESCAPE = '\\'
        val NEWEST_FIRST: SortOrders = mapOf("newest" to listOf(NewsletterSubscriptionTbl.subscribedAt to SortOrder.DESC))
    }
}
