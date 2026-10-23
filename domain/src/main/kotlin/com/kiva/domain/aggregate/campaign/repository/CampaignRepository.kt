package com.kiva.domain.aggregate.campaign.repository

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.campaign.entity.Campaign
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignId
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignWindow
import java.time.Instant

/**
 * Campaigns, a row each. Their slugs are unique (`campaign_tbl_slug_key`) and no two active ones overlap
 * (`campaign_no_overlap`, an EXCLUDE constraint): CampaignDomainService checks first, under [lockAll], and the database
 * is the last wall ([add] and [save] then throw UniqueTakenException or CampaignOverlapException).
 */
interface CampaignRepository {

    fun nextId(): CampaignId

    /**
     * Takes the lock every write of a campaign takes first (an advisory lock on `campaigns`), so two writes never check
     * the slug and the overlap at the same moment. Held until the transaction ends.
     */
    fun lockAll()

    fun find(id: CampaignId): Campaign?

    /** Every campaign, the latest start first (decision 3's panel list). */
    fun findAll(): List<Campaign>

    fun findBySlug(slug: Slug): Campaign?

    /** An active campaign other than [except] whose window shares a moment with [window], if there is one. */
    fun findActiveOverlapping(window: CampaignWindow, except: CampaignId?): Campaign?

    /** The campaign the shop shows at [now], if any (at most one: active ones never overlap). */
    fun findLiveAt(now: Instant): Campaign?

    fun add(campaign: Campaign)

    fun save(campaign: Campaign)

    /** Removes it for good; false when there was none. */
    fun delete(id: CampaignId): Boolean
}

/** Another write made an active campaign overlapping this one's window a moment before (the EXCLUDE constraint). */
class CampaignOverlapException(cause: Throwable) : RuntimeException("an active campaign overlaps this one's window", cause)
