package com.kiva.domain.aggregate.newsletter.entity

import com.kiva.domain.aggregate.newsletter.model.valueobject.SubscriptionStatus
import com.kiva.domain.aggregate.newsletter.model.valueobject.UnsubscribeToken
import com.kiva.domain.aggregate.user.model.valueobject.Email
import java.time.Instant

/**
 * A newsletter subscriber (roadmap phase 13): an email, ACTIVE or UNSUBSCRIBED, with its unsubscribe token. The shop
 * only keeps the list; the newsletter is sent with an outside tool (decision 16).
 */
class NewsletterSubscription private constructor(
    val id: Long?,
    val email: Email,
    val status: SubscriptionStatus,
    val source: String?,
    val token: UnsubscribeToken,
    val subscribedAt: Instant,
    val unsubscribedAt: Instant?,
) {
    init {
        require((status == SubscriptionStatus.UNSUBSCRIBED) == (unsubscribedAt != null)) { "Subscription $id: unsubscribedAt goes with UNSUBSCRIBED" }
    }

    override fun toString(): String = "NewsletterSubscription($id, $status)"

    /** What subscribing again did (decision 12): the answer is the same either way, only a welcome mail tells them apart. */
    enum class Outcome { NEW, REACTIVATED, ALREADY_ACTIVE }

    /** Subscribing an email already in the list: an unsubscribed one is ACTIVE again from [now]; an active one stays. */
    fun subscribedAgain(now: Instant): Pair<NewsletterSubscription, Outcome> = when (status) {
        SubscriptionStatus.ACTIVE -> this to Outcome.ALREADY_ACTIVE
        SubscriptionStatus.UNSUBSCRIBED ->
            NewsletterSubscription(id, email, SubscriptionStatus.ACTIVE, source, token, subscribedAt = now, unsubscribedAt = null) to Outcome.REACTIVATED
    }

    /** UNSUBSCRIBED from [now]; null when it already is (nothing changes, decision 15). */
    fun unsubscribed(now: Instant): NewsletterSubscription? = when (status) {
        SubscriptionStatus.UNSUBSCRIBED -> null
        SubscriptionStatus.ACTIVE -> NewsletterSubscription(id, email, SubscriptionStatus.UNSUBSCRIBED, source, token, subscribedAt, unsubscribedAt = now)
    }

    companion object {
        /** A new subscriber, ACTIVE; its id is the database's. */
        fun create(email: Email, source: String?, token: UnsubscribeToken, now: Instant) =
            NewsletterSubscription(null, email, SubscriptionStatus.ACTIVE, source, token, now, null)

        fun constructFromPersisted(
            id: Long,
            email: Email,
            status: SubscriptionStatus,
            source: String?,
            token: UnsubscribeToken,
            subscribedAt: Instant,
            unsubscribedAt: Instant?,
        ) = NewsletterSubscription(id, email, status, source, token, subscribedAt, unsubscribedAt)
    }
}
