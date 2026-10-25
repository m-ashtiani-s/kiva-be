package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.domain.aggregate.newsletter.entity.NewsletterSubscription
import kotlinx.serialization.Serializable

/** The newsletter's Persian words (roadmap phase 13, decision 12). */
object NewsletterTexts {
    /** The one answer to subscribing, whether new, back or already there. */
    const val SUBSCRIBED = "عضو خبرنامه کیوا شدی!"
}

/** Contract schema `MessageResponse`: one sentence for the client to show. */
@Serializable
data class MessageHttpResponse(val message: String)

/** Contract schema `NewsletterSubscription`, for the panel's list. */
@Serializable
data class NewsletterSubscriptionHttpResponse(
    val id: Long,
    val email: String,
    val status: String,
    val source: String?,
    val subscribedAt: String,
    val unsubscribedAt: String?,
) {
    override fun toString(): String = "NewsletterSubscriptionHttpResponse($id, $status)"

    companion object {
        fun from(subscription: NewsletterSubscription) = NewsletterSubscriptionHttpResponse(
            id = checkNotNull(subscription.id),
            email = subscription.email.value,
            status = subscription.status.name,
            source = subscription.source,
            subscribedAt = subscription.subscribedAt.toString(),
            unsubscribedAt = subscription.unsubscribedAt?.toString(),
        )
    }
}
