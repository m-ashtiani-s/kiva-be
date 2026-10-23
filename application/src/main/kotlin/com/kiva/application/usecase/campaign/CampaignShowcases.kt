package com.kiva.application.usecase.campaign

import com.kiva.application.dto.CampaignDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.product.ProductCards
import com.kiva.domain.aggregate.campaign.entity.Campaign
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Instant

/**
 * Campaigns with their products as shop cards (Rule 15): the panel's list and form, `GET /campaigns/active` and the home
 * page all show a campaign the same way. The read model picks and orders the products (decision 4); the cards are made
 * exactly as in the shop's list (ProductCards), with no heart (`isWishlisted = null`: these answers are public or
 * cached). One read of the products for any number of campaigns. Inside the caller's transaction.
 */
class CampaignShowcases(
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
) {

    /** Each of [campaigns] with at most [limit] products, in the given order. */
    fun of(campaigns: List<Campaign>, limit: Int, now: Instant): List<CampaignDto> {
        if (campaigns.isEmpty()) return emptyList()
        // Every candidate at once (every discounted product in the shop, or those the campaigns chose), then each
        // campaign's own: the order is the read model's, so a filter keeps it.
        val anyAll = campaigns.any { it.productIds.isEmpty() }
        val chosen = campaigns.flatMap { it.productIds }.toSet()
        val candidates = productQmRepository.campaignShowcase(if (anyAll) null else chosen, Int.MAX_VALUE)
        val categories = if (candidates.isEmpty()) emptyMap() else categoryRepository.findAll().associateBy { it.slug }
        val palette = if (candidates.isEmpty()) emptyMap() else colorRepository.findAll().associateBy { it.key }
        val settings = shopSettingsRepository.get()
        val cards = candidates.associate { qm ->
            qm.productId to ProductCards.cardOf(qm, categories.getValue(qm.categorySlug), palette, now, settings.freeShippingThreshold, settings.lowStockThreshold)
        }
        return campaigns.map { campaign ->
            val ids = campaign.productIds.toSet()
            val own = if (ids.isEmpty()) candidates else candidates.filter { it.productId in ids }
            CampaignDto(campaign, own.take(limit).map { cards.getValue(it.productId) }, now)
        }
    }

    fun of(campaign: Campaign, limit: Int, now: Instant): CampaignDto = of(listOf(campaign), limit, now).single()
}
