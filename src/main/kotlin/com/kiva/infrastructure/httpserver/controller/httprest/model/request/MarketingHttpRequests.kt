package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.application.usecase.banner.BannerFields
import com.kiva.application.usecase.campaign.CampaignFields
import com.kiva.application.usecase.testimonial.TestimonialFields
import kotlinx.serialization.Serializable

/**
 * Contract schema `CampaignUpsertRequest`, the body of `POST` and `PUT /admin/campaigns*`. Every field nullable with a
 * default, so one left out is its default or, for the required ones, reported with every other problem. [startsAt] and
 * [endsAt] are read as text, so a malformed one is 400 DATE_TIME_INVALID on its field, not an unreadable body.
 */
@Serializable
data class CampaignUpsertHttpRequest(
    val slug: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val icon: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val productIds: List<Long>? = null,
    val isActive: Boolean? = null,
) {
    fun toFields() = CampaignFields(slug, title, subtitle, icon, startsAt, endsAt, productIds, isActive)
}

/** Contract schema `TestimonialUpsertRequest`, the body of `POST` and `PUT /admin/testimonials*`; every field nullable with a default. */
@Serializable
data class TestimonialUpsertHttpRequest(
    val customerName: String? = null,
    val city: String? = null,
    val source: String? = null,
    val message: String? = null,
    val messageTime: String? = null,
    val receivedAt: String? = null,
    val rating: Int? = null,
    val productId: Long? = null,
    val colorKey: String? = null,
    val screenshotMediaId: String? = null,
    val isPublished: Boolean? = null,
    val sortOrder: Int? = null,
) {
    override fun toString(): String = "TestimonialUpsertHttpRequest(productId=$productId)"

    fun toFields() = TestimonialFields(customerName, city, source, message, messageTime, receivedAt, rating, productId, colorKey, screenshotMediaId, isPublished, sortOrder)
}

/** Contract schema `BannerUpsertRequest`, the body of `POST` and `PUT /admin/banners*`; every field nullable with a default. */
@Serializable
data class BannerUpsertHttpRequest(
    val placement: String? = null,
    val theme: String? = null,
    val tagLabel: String? = null,
    val tagIcon: String? = null,
    val title: String? = null,
    val text: String? = null,
    val ctaLabel: String? = null,
    val ctaUrl: String? = null,
    val imageMediaId: String? = null,
    val overlayHighlight: String? = null,
    val overlayTitle: String? = null,
    val overlaySubtitle: String? = null,
    val overlayChannel: String? = null,
    val sortOrder: Int? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
) {
    fun toFields() = BannerFields(
        placement, theme, tagLabel, tagIcon, title, text, ctaLabel, ctaUrl, imageMediaId, overlayHighlight, overlayTitle, overlaySubtitle, overlayChannel,
        sortOrder, startsAt, endsAt,
    )
}
