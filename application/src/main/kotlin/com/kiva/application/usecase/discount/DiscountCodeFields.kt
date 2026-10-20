package com.kiva.application.usecase.discount

import com.kiva.application.usecase.product.MomentField
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.discount.model.DiscountTerms

/**
 * The contract's DiscountCodeUpsertRequest as it arrived, every field raw and nullable: the same form for `POST` and
 * `PUT /admin/discount-codes*` (CreateDiscountCodeUc, UpdateDiscountCodeUc). A field left out is its contract default
 * (`isActive` true, the switches false, the limits none); only `code`, `type`, `value` and `label` are required.
 */
data class DiscountCodeFields(
    val code: String?,
    val type: String?,
    val value: Long?,
    val label: String?,
    val minSubtotal: Long?,
    val maxDiscount: Long?,
    val startsAt: String?,
    val endsAt: String?,
    val usageLimit: Int?,
    val perUserLimit: Int?,
    val firstOrderOnly: Boolean?,
    val requiresLogin: Boolean?,
    val excludeSaleItems: Boolean?,
    val categoryIds: List<Long>?,
    val productIds: List<Long>?,
    val isActive: Boolean?,
) {
    /** Every problem of the form at once, each under its field; [legacyAllowed] for an edit (DiscountCodeText.makeNewInPanel). */
    fun check(legacyAllowed: Boolean = false): Result<DiscountTerms, InputProblems> = DiscountTerms.makeNew(
        code = code,
        type = type,
        value = value,
        label = label,
        minSubtotal = minSubtotal,
        maxDiscount = maxDiscount,
        startsAt = MomentField.makeNew(startsAt),
        endsAt = MomentField.makeNew(endsAt),
        usageLimit = usageLimit,
        perUserLimit = perUserLimit,
        firstOrderOnly = firstOrderOnly,
        requiresLogin = requiresLogin,
        excludeSaleItems = excludeSaleItems,
        categoryIds = categoryIds,
        productIds = productIds,
        isActive = isActive,
        legacyAllowed = legacyAllowed,
    )
}
