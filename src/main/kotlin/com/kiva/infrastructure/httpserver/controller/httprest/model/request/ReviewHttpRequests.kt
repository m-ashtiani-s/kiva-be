package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.common.patch.Patch
import com.kiva.infrastructure.common.http.PatchSerializer
import kotlinx.serialization.Serializable

/** The body of `POST /products/{productId}/reviews` (contract CreateReviewRequest). Personal fields: [toString] hides them. */
@Serializable
data class CreateReviewHttpRequest(
    val rating: Int? = null,
    val authorName: String? = null,
    val contact: String? = null,
    val text: String? = null,
    val orderCode: String? = null,
    val orderItemId: Int? = null,
    val mediaIds: List<String?>? = null,
) {
    override fun toString(): String = "CreateReviewHttpRequest(rating=$rating, orderCode=$orderCode, mediaIds=${mediaIds?.size})"
}

/** The body of `PATCH /me/reviews/{reviewId}` (contract UpdateReviewRequest): a field not sent stays. */
@Serializable
data class UpdateReviewHttpRequest(
    @Serializable(with = PatchSerializer::class) val rating: Patch<Int?> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val authorName: Patch<String?> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val text: Patch<String?> = Patch.Unchanged,
) {
    override fun toString(): String = "UpdateReviewHttpRequest(rating=$rating)"
}

/** The body of `PUT /reviews/{reviewId}/helpful`. */
@Serializable
data class ReviewHelpfulHttpRequest(val helpful: Boolean? = null)

/** The body of `POST /admin/reviews/{reviewId}/moderation` (contract ReviewModerationRequest). Texts: [toString] hides them. */
@Serializable
data class ReviewModerationHttpRequest(val action: String? = null, val rejectionReason: String? = null, val reply: String? = null) {
    override fun toString(): String = "ReviewModerationHttpRequest(action=$action)"
}

/** The body of `PUT /admin/reviews/{reviewId}/reply`. */
@Serializable
data class ReviewReplyHttpRequest(val text: String? = null) {
    override fun toString(): String = "ReviewReplyHttpRequest(***)"
}
