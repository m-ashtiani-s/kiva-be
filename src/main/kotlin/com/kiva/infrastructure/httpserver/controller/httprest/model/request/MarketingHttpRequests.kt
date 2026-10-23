package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.application.usecase.campaign.CampaignFields
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
