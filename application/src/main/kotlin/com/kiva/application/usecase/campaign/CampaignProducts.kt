package com.kiva.application.usecase.campaign

import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.under
import com.kiva.domain.aggregate.campaign.model.CampaignDraft
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus

/**
 * The products a campaign names must be there and not archived (roadmap section 16.6): one that isn't is 400 on its own
 * index (`productIds.2 PRODUCT_NOT_FOUND`), the contract's policy for a reference inside a body. A draft may be named:
 * it shows once published. The same check for making and replacing; inside the caller's transaction, one read.
 */
class CampaignProducts(private val productQmRepository: ProductQmRepository) {

    /** The problem of [draft]'s products, or null when they are all fine. */
    fun problemOf(draft: CampaignDraft): ResultFailure? {
        if (draft.productIds.isEmpty()) return null
        val found = productQmRepository.findBriefs(draft.productIds)
        val missing = draft.productIds.withIndex()
            .filter { (_, id) -> found[id]?.status.let { it == null || it == ProductStatus.ARCHIVED } }
            .map { ProductNotFound.under("${it.index}") }
        return missing.takeIf { it.isNotEmpty() }?.under("productIds")
    }

    data object ProductNotFound : ResultFailure("PRODUCT_NOT_FOUND", FailureKind.INVALID)
}
