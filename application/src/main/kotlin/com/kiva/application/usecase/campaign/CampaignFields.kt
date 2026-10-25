package com.kiva.application.usecase.campaign

import com.kiva.application.usecase.product.MomentField
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.campaign.model.CampaignDraft

/**
 * The contract's CampaignUpsertRequest as it arrived, every field raw and nullable: the same form for `POST` and `PUT
 * /admin/campaigns*` (CreateCampaignUc, UpdateCampaignUc). `PUT` replaces the whole campaign, so a field left out is its
 * default (`isActive` true, no subtitle, no icon, every discounted product).
 */
data class CampaignFields(
    val slug: String?,
    val title: String?,
    val subtitle: String?,
    val icon: String?,
    val startsAt: String?,
    val endsAt: String?,
    val productIds: List<Long>?,
    val isActive: Boolean?,
) {
    /** Every problem of the form at once, each under its field. */
    fun check(): Result<CampaignDraft, InputProblems> =
        CampaignDraft.makeNew(slug, title, subtitle, icon, MomentField.makeNew(startsAt), MomentField.makeNew(endsAt), productIds, isActive)
}
