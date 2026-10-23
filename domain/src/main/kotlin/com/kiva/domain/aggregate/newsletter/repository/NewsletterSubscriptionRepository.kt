package com.kiva.domain.aggregate.newsletter.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.domain.aggregate.newsletter.entity.NewsletterSubscription
import com.kiva.domain.aggregate.newsletter.model.valueobject.SubscriptionStatus
import com.kiva.domain.aggregate.newsletter.model.valueobject.UnsubscribeToken
import com.kiva.domain.aggregate.user.model.valueobject.Email
import java.time.Instant

/** Newsletter subscribers, by their email. Runs inside the use case's transaction. */
interface NewsletterSubscriptionRepository {
    /**
     * Stores a new subscriber; false (and nothing stored) when its email, or by chance its token, is already there
     * (`ON CONFLICT DO NOTHING`, so the transaction goes on). Two subscribing the same email at once: one inserts, the
     * other waits for it and inserts nothing.
     */
    fun addIfAbsent(subscription: NewsletterSubscription): Boolean

    /** The subscriber of [email], its row held until the transaction ends. */
    fun lockFind(email: Email): NewsletterSubscription?

    /** The subscriber of [token], its row held until the transaction ends. */
    fun lockFindByToken(token: UnsubscribeToken): NewsletterSubscription?

    /** Writes back one read with a lock. */
    fun save(subscription: NewsletterSubscription)

    /** The panel's list (decision 17): newest subscribed first; [Filter.q] a part of the email. */
    fun page(filter: Filter, pageable: Pageable): Page<NewsletterSubscription>

    /** Deletes those UNSUBSCRIBED before [before] (decision 19); how many went. */
    fun deleteUnsubscribedBefore(before: Instant): Int

    data class Filter(val status: SubscriptionStatus?, val q: String?)
}
