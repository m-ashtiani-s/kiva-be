package com.kiva.application.usecase.campaign

import com.kiva.application.repository.ProductFilter
import com.kiva.domain.aggregate.campaign.entity.Campaign
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Instant

/**
 * The campaign the shop shows at a moment (roadmap section 16.2, decision 3), for the shop's list (`?campaign=`), its
 * facets and a product's page (its countdown). Like WishlistHearts: [NONE] for what never shows a campaign. Inside the
 * caller's transaction.
 */
fun interface LiveCampaign {
    fun at(now: Instant): Campaign?

    /**
     * [filter] narrowed to its campaign (decision 5), with that campaign when it runs now: only its products (or every
     * product, with none chosen) and only the discounted ones. A slug of no running campaign gives no product, not an
     * error. A filter without a campaign is as it is.
     */
    fun scope(filter: ProductFilter, now: Instant): Pair<ProductFilter, Campaign?> {
        val slug = filter.campaign ?: return filter to null
        val live = at(now)?.takeIf { it.slug == slug } ?: return filter.copy(campaignProductIds = emptySet()) to null
        return filter.copy(onSale = true, campaignProductIds = live.productIds.toSet().ifEmpty { null }) to live
    }

    /** [productId] is one the live campaign shows (decision 6), when it is discounted: the countdown on its page. */
    fun showing(productId: ProductId, discounted: Boolean, now: Instant): Campaign? {
        if (!discounted) return null
        return at(now)?.takeIf { it.productIds.isEmpty() || productId in it.productIds }
    }

    companion object {
        /** For what reads no campaign: none runs. */
        val NONE = LiveCampaign { null }

        fun of(campaignRepository: CampaignRepository) = LiveCampaign(campaignRepository::findLiveAt)
    }
}
