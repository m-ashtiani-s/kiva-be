package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.BannerDto
import com.kiva.application.dto.CampaignDto
import com.kiva.application.dto.TestimonialDto
import com.kiva.application.dto.TestimonialListDto
import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel
import com.kiva.infrastructure.common.http.PageMetaHttpResponse
import com.kiva.infrastructure.common.http.toHttpResponse
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

/** The Persian words of marketing: each messenger's name, as a testimonial shows where it came from. */
object MarketingTexts {
    fun sourceName(channel: SocialChannel): String = when (channel) {
        SocialChannel.RUBIKA -> "روبیکا"
        SocialChannel.TELEGRAM -> "تلگرام"
        SocialChannel.BALE -> "بله"
        SocialChannel.INSTAGRAM -> "اینستاگرام"
    }
}

/** Contract schema `Testimonial`: every field always there; the screenshot is its 800-wide picture (or the largest). */
@Serializable
data class TestimonialHttpResponse(
    val id: Long,
    val customerName: String,
    val customerInitial: String,
    val city: String?,
    val source: String,
    val sourceName: String,
    val message: String,
    val messageTime: String?,
    val receivedAt: String?,
    val rating: Int,
    val product: ProductRefHttpResponse,
    val color: ColorHttpResponse?,
    val screenshotUrl: String?,
) {
    companion object {
        fun from(dto: TestimonialDto, urls: MediaUrls): TestimonialHttpResponse {
            val testimonial = dto.testimonial
            return TestimonialHttpResponse(
                id = testimonial.id.value,
                customerName = testimonial.customerName,
                customerInitial = testimonial.customerName.take(1),
                city = testimonial.city,
                source = testimonial.source.name,
                sourceName = MarketingTexts.sourceName(testimonial.source),
                message = testimonial.message,
                messageTime = testimonial.messageTime,
                receivedAt = testimonial.receivedAt?.toString(),
                rating = testimonial.rating.value,
                product = ReviewHttpResponse.refOf(dto.product, urls).copy(colorKey = dto.color?.key?.value),
                color = dto.color?.let { ColorHttpResponse(it.key.value, it.name.value, it.hex.value, it.isLight, it.sortOrder) },
                screenshotUrl = dto.screenshot?.let { shot -> urls.of(shot.variants.firstOrNull { it.width == SCREENSHOT_WIDTH }?.path ?: shot.mainPath) },
            )
        }

        /** The screenshot's width the site shows (roadmap section 16.6). */
        private const val SCREENSHOT_WIDTH = 800
    }
}

/** Contract schema `AdminTestimonial`: `Testimonial` and what only the panel's form needs. */
@Serializable
data class AdminTestimonialHttpResponse(
    val id: Long,
    val customerName: String,
    val customerInitial: String,
    val city: String?,
    val source: String,
    val sourceName: String,
    val message: String,
    val messageTime: String?,
    val receivedAt: String?,
    val rating: Int,
    val product: ProductRefHttpResponse,
    val color: ColorHttpResponse?,
    val screenshotUrl: String?,
    val isPublished: Boolean,
    val sortOrder: Int,
    val screenshotMediaId: String?,
    val createdAt: String,
) {
    companion object {
        fun from(dto: TestimonialDto, urls: MediaUrls): AdminTestimonialHttpResponse {
            val shop = TestimonialHttpResponse.from(dto, urls)
            val testimonial = dto.testimonial
            return AdminTestimonialHttpResponse(
                shop.id, shop.customerName, shop.customerInitial, shop.city, shop.source, shop.sourceName, shop.message, shop.messageTime, shop.receivedAt,
                shop.rating, shop.product, shop.color, shop.screenshotUrl, testimonial.isPublished, testimonial.sortOrder, testimonial.screenshotMediaId?.value,
                testimonial.createdOn.toString(),
            )
        }
    }
}

/** Contract schema `TestimonialListResponse`: the summary the panel typed (null until it does) and one page. */
@Serializable
data class TestimonialListHttpResponse(
    val summary: SatisfactionSummaryHttpResponse?,
    val items: List<TestimonialHttpResponse>,
    val meta: PageMetaHttpResponse,
) {
    companion object {
        fun from(list: TestimonialListDto, urls: MediaUrls): TestimonialListHttpResponse {
            val page = list.page.toHttpResponse { TestimonialHttpResponse.from(it, urls) }
            return TestimonialListHttpResponse(list.summary?.let(SatisfactionSummaryHttpResponse::from), page.items, page.meta)
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
