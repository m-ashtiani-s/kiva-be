package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.CampaignDto
import com.kiva.domain.aggregate.campaign.model.policy.CampaignShowcasePolicy
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/** Contract schema `Campaign`: every field always there; its products are shop cards with no heart. */
@Serializable
data class CampaignHttpResponse(
    val id: Long,
    val slug: String,
    val title: String,
    val subtitle: String?,
    val icon: String?,
    val startsAt: String,
    val endsAt: String,
    val serverTime: String,
    val products: List<ProductSummaryHttpResponse>,
    val seeAllUrl: String,
    val isActive: Boolean,
) {
    companion object {
        fun from(dto: CampaignDto, urls: MediaUrls): CampaignHttpResponse {
            val campaign = dto.campaign
            return CampaignHttpResponse(
                id = campaign.id.value,
                slug = campaign.slug.value,
                title = campaign.title,
                subtitle = campaign.subtitle,
                icon = campaign.icon?.value,
                startsAt = campaign.window.startsAt.toString(),
                endsAt = campaign.window.endsAt.toString(),
                serverTime = dto.serverTime.toString(),
                products = dto.products.map { ProductSummaryHttpResponse.from(it, filterColors = emptyList(), urls) },
                seeAllUrl = CampaignShowcasePolicy.seeAllUrl(campaign.slug),
                isActive = campaign.isActive,
            )
        }
    }
}

/** Contract schema `AdminCampaign`: `Campaign` and the panel's own choice of products, for the form. */
@Serializable
data class AdminCampaignHttpResponse(
    val id: Long,
    val slug: String,
    val title: String,
    val subtitle: String?,
    val icon: String?,
    val startsAt: String,
    val endsAt: String,
    val serverTime: String,
    val products: List<ProductSummaryHttpResponse>,
    val seeAllUrl: String,
    val isActive: Boolean,
    val productIds: List<Long>,
) {
    companion object {
        fun from(dto: CampaignDto, urls: MediaUrls): AdminCampaignHttpResponse {
            val shop = CampaignHttpResponse.from(dto, urls)
            return AdminCampaignHttpResponse(
                shop.id, shop.slug, shop.title, shop.subtitle, shop.icon, shop.startsAt, shop.endsAt, shop.serverTime, shop.products,
                shop.seeAllUrl, shop.isActive, dto.campaign.productIds.map { it.value },
            )
        }
    }
}
