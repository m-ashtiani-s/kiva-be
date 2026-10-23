package com.kiva.application.dto

import com.kiva.application.repository.SiteContentQm
import com.kiva.domain.aggregate.settings.entity.ShopSettings

/**
 * The home page (contract HomePage, before its texts are filled): its fixed sections as the content keeps them, and
 * the parts that come from the shop, every card without a heart (the answer is cached).
 *
 * @property settings the settings of this moment, for the texts that name one (`{holdDays}`).
 * @property showcase the product whose colours turn in the hero; null in an empty shop.
 * @property sale the running campaign with its first products; null when none runs.
 */
data class HomePageDto(
    val home: SiteContentQm.Home,
    val settings: ShopSettings,
    val showcase: ProductCardDto?,
    val categories: List<CategoryDto>,
    val newArrivals: List<ProductCardDto>,
    val promos: List<BannerDto>,
    val sale: CampaignDto?,
    val testimonials: TestimonialListDto,
)
