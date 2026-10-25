package com.kiva.application.usecase.discount

import com.kiva.application.port.AuditChange
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.domain.aggregate.discount.model.DiscountTerms

/** The `changes` of a DISCOUNT_CODE_UPDATED row: every term that changed, the lists as their sorted ids. */
internal object DiscountCodeAudit {

    fun changes(before: DiscountTerms, after: DiscountTerms): List<AuditChange> = AuditDiff.build {
        field("code", before.code.value, after.code.value)
        field("type", before.type, after.type)
        field("value", before.value, after.value)
        field("label", before.label, after.label)
        field("minSubtotal", before.minSubtotal, after.minSubtotal)
        field("maxDiscount", before.maxDiscount, after.maxDiscount)
        field("startsAt", before.startsAt, after.startsAt)
        field("endsAt", before.endsAt, after.endsAt)
        field("usageLimit", before.usageLimit, after.usageLimit)
        field("perUserLimit", before.perUserLimit, after.perUserLimit)
        field("firstOrderOnly", before.firstOrderOnly, after.firstOrderOnly)
        field("requiresLogin", before.requiresLogin, after.requiresLogin)
        field("excludeSaleItems", before.excludeSaleItems, after.excludeSaleItems)
        list("categoryIds", before.categoryIds.map { it.value }.sorted(), after.categoryIds.map { it.value }.distinct().sorted())
        list("productIds", before.productIds.map { it.value }.sorted(), after.productIds.map { it.value }.distinct().sorted())
        field("isActive", before.isActive, after.isActive)
    }
}
