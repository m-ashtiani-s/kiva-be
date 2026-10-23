package com.kiva.domain.aggregate.campaign.entity

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.campaign.model.CampaignDraft
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignId
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignWindow
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import java.time.Instant

/**
 * A campaign (جشنواره; roadmap phase 11, the contract's Campaign): a countdown and a list of discounted products, shown
 * while it runs. Kept as a row; the panel makes, replaces and deletes it.
 *
 * Rules it keeps itself: its window ends after it starts, and its products are at most 200, each once. What it can't
 * know: whether its slug is free and whether another active campaign shares a moment of its window
 * (CampaignDomainService), and whether its products are in the shop (the use case, from the read model).
 *
 * **It changes no price** (decision 1): each product's discount is its own `compareAtPrice`. Ending or deleting a
 * campaign gives nothing back, and nothing runs when it ends.
 *
 * @property productIds the panel's choice; empty: every discounted product in the shop (decision 4).
 */
class Campaign private constructor(
    val id: CampaignId,
    val slug: Slug,
    val title: String,
    val subtitle: String?,
    val icon: IconName?,
    val window: CampaignWindow,
    val productIds: List<ProductId>,
    val isActive: Boolean,
    val createdOn: Instant,
    val updatedOn: Instant,
) {
    // The draft and the table's CHECKs say the same: a broken row is a bug.
    init {
        require(title.isNotBlank()) { "Campaign ${id.value} has no title" }
        require(productIds.size <= CampaignDraft.MAX_PRODUCTS && productIds.toSet().size == productIds.size) { "Campaign ${id.value}: bad products" }
    }

    /** It is the one the shop shows at [now] (decision 3): switched on, and [now] inside its window. */
    fun isLiveAt(now: Instant): Boolean = isActive && window.contains(now)

    /** The campaign as [draft] makes it, or null when [draft] would leave it as it is (no save, no change log row). */
    fun replacedBy(draft: CampaignDraft, now: Instant): Campaign? {
        if (draft.slug == slug && draft.title == title && draft.subtitle == subtitle && draft.icon == icon && draft.window == window &&
            draft.productIds == productIds && draft.isActive == isActive
        ) {
            return null
        }
        return Campaign(id, draft.slug, draft.title, draft.subtitle, draft.icon, draft.window, draft.productIds, draft.isActive, createdOn, now)
    }

    override fun toString(): String = "Campaign(${id.value})"

    companion object {
        fun create(id: CampaignId, draft: CampaignDraft, now: Instant): Campaign =
            Campaign(id, draft.slug, draft.title, draft.subtitle, draft.icon, draft.window, draft.productIds, draft.isActive, now, now)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            id: CampaignId,
            slug: Slug,
            title: String,
            subtitle: String?,
            icon: IconName?,
            window: CampaignWindow,
            productIds: List<ProductId>,
            isActive: Boolean,
            createdOn: Instant,
            updatedOn: Instant,
        ): Campaign = Campaign(id, slug, title, subtitle, icon, window, productIds, isActive, createdOn, updatedOn)
    }
}
