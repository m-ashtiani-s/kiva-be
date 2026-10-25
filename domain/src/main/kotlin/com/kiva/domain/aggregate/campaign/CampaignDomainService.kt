package com.kiva.domain.aggregate.campaign

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.campaign.model.CampaignDraft
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignId
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository

/**
 * The rules of saving a campaign that one campaign can't know alone (roadmap section 16.6): its slug is nobody else's
 * (409 DUPLICATE_SLUG) and, when it is active, no other active campaign shares a moment of its window (409
 * CAMPAIGN_OVERLAP with that campaign's id). In this order. Runs inside the caller's transaction, after
 * CampaignRepository.lockAll; it never saves.
 */
class CampaignDomainService(private val campaignRepository: CampaignRepository) {

    /** [self]: the campaign being replaced, or null for a new one. */
    fun checkSave(draft: CampaignDraft, self: CampaignId?): Result<Unit, Failure> {
        val sameSlug = campaignRepository.findBySlug(draft.slug)
        if (sameSlug != null && sameSlug.id != self) return Result.failure(Failure.DuplicateSlug)
        if (draft.isActive) {
            val other = campaignRepository.findActiveOverlapping(draft.window, except = self)
            if (other != null) return Result.failure(Failure.Overlap(other.id))
        }
        return Result.success(Unit)
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        data object DuplicateSlug : Failure("DUPLICATE_SLUG", FailureKind.CONFLICT, "slug")

        /** [campaignId] null only when the database caught it (a write a moment before); the check always knows it. */
        data class Overlap(val campaignId: CampaignId?) :
            Failure("CAMPAIGN_OVERLAP", FailureKind.CONFLICT, meta = campaignId?.let { mapOf("campaignId" to it.value) } ?: emptyMap())
    }
}
