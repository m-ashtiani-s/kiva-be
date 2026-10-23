package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.BannerDto
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

/** Contract schema `Banner`: every field always there; no tag, button or overlay is null. */
@Serializable
data class BannerHttpResponse(
    val id: Long,
    val placement: String,
    val theme: String,
    val tag: BannerTagHttpResponse?,
    val title: String,
    val text: String?,
    val cta: BannerCtaHttpResponse?,
    val image: MediaAssetHttpResponse,
    val overlay: BannerOverlayHttpResponse?,
    val sortOrder: Int,
    val startsAt: String?,
    val endsAt: String?,
) {
    companion object {
        fun from(dto: BannerDto, urls: MediaUrls): BannerHttpResponse {
            val banner = dto.banner
            return BannerHttpResponse(
                id = banner.id.value,
                placement = banner.placement.name,
                theme = banner.theme.name,
                tag = banner.tag?.let { BannerTagHttpResponse(it.label, it.icon?.value) },
                title = banner.title,
                text = banner.text,
                cta = banner.cta?.let { BannerCtaHttpResponse(it.label, it.url.value) },
                image = MediaAssetHttpResponse.from(dto.image, urls),
                overlay = banner.overlay?.let { BannerOverlayHttpResponse(it.highlight, it.title, it.subtitle, it.channel?.name) },
                sortOrder = banner.sortOrder,
                startsAt = banner.window.startsAt?.toString(),
                endsAt = banner.window.endsAt?.toString(),
            )
        }
    }
}

@Serializable
data class BannerTagHttpResponse(val label: String, val icon: String?)

@Serializable
data class BannerCtaHttpResponse(val label: String, val url: String)

@Serializable
data class BannerOverlayHttpResponse(val highlight: String?, val title: String?, val subtitle: String?, val channel: String?)

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
