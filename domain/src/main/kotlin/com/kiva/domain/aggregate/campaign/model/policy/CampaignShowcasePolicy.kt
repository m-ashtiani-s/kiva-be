package com.kiva.domain.aggregate.campaign.model.policy

import com.kiva.common.valueobject.Slug

/**
 * Which of a campaign's products are shown, and how many (roadmap section 16.2, decisions 4 and 5): its products (or,
 * with none chosen, every product in the shop), only the discounted ones with a color left, the biggest discount first,
 * then the best selling, then the id. The read model applies it (ProductQmRepository.campaignShowcase).
 */
object CampaignShowcasePolicy {
    /** On the home page (`HomePage.sale`). */
    const val LIMIT_FOR_HOME = 4

    /** `GET /campaigns/active`. */
    const val LIMIT_FOR_ACTIVE = 24

    /** A discount of at least this many percent makes a product «discounted». */
    const val MIN_DISCOUNT_PERCENT = 1

    /** «همه رو ببین»: the shop's list of the campaign's discounted products. */
    fun seeAllUrl(slug: Slug): String = "/products?campaign=${slug.value}"
}
