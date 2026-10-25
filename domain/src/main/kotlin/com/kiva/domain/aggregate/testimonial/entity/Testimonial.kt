package com.kiva.domain.aggregate.testimonial.entity

import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel
import com.kiva.domain.aggregate.testimonial.model.TestimonialDraft
import com.kiva.domain.aggregate.testimonial.model.valueobject.TestimonialId
import java.time.Instant

/**
 * A testimonial (پیام رضایت; roadmap phase 11, the contract's Testimonial): a customer's message from a messenger, typed
 * by the panel from its screenshot. Kept as a row; the panel makes, replaces and deletes it, and publishes it or not.
 *
 * What it can't know: whether its product is there and its colour one of it (the use case, from the read model), and
 * whether its screenshot is a TESTIMONIAL image (MediaAttachments).
 */
class Testimonial private constructor(
    val id: TestimonialId,
    val customerName: String,
    val city: String?,
    val source: SocialChannel,
    val message: String,
    val messageTime: String?,
    val receivedAt: Instant?,
    val rating: StarRating,
    val productId: ProductId,
    val colorKey: ColorKey?,
    val screenshotMediaId: MediaId?,
    val isPublished: Boolean,
    val sortOrder: Int,
    val createdOn: Instant,
    val updatedOn: Instant,
) {
    init {
        require(customerName.isNotBlank() && message.isNotBlank()) { "Testimonial ${id.value} has no name or no message" }
    }

    /** The testimonial as [draft] makes it, or null when [draft] would leave it as it is (no save, no change log row). */
    fun replacedBy(draft: TestimonialDraft, now: Instant): Testimonial? {
        val same = draft.customerName == customerName && draft.city == city && draft.source == source && draft.message == message &&
            draft.messageTime == messageTime && draft.receivedAt == receivedAt && draft.rating == rating && draft.productId == productId &&
            draft.colorKey == colorKey && draft.screenshotMediaId == screenshotMediaId && draft.isPublished == isPublished && draft.sortOrder == sortOrder
        if (same) return null
        return of(id, draft, createdOn, now)
    }

    override fun toString(): String = "Testimonial(${id.value})"

    companion object {
        fun create(id: TestimonialId, draft: TestimonialDraft, now: Instant): Testimonial = of(id, draft, now, now)

        private fun of(id: TestimonialId, draft: TestimonialDraft, createdOn: Instant, updatedOn: Instant) = Testimonial(
            id, draft.customerName, draft.city, draft.source, draft.message, draft.messageTime, draft.receivedAt, draft.rating, draft.productId,
            draft.colorKey, draft.screenshotMediaId, draft.isPublished, draft.sortOrder, createdOn, updatedOn,
        )

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            id: TestimonialId,
            customerName: String,
            city: String?,
            source: SocialChannel,
            message: String,
            messageTime: String?,
            receivedAt: Instant?,
            rating: StarRating,
            productId: ProductId,
            colorKey: ColorKey?,
            screenshotMediaId: MediaId?,
            isPublished: Boolean,
            sortOrder: Int,
            createdOn: Instant,
            updatedOn: Instant,
        ): Testimonial = Testimonial(
            id, customerName, city, source, message, messageTime, receivedAt, rating, productId, colorKey, screenshotMediaId, isPublished, sortOrder,
            createdOn, updatedOn,
        )
    }
}
