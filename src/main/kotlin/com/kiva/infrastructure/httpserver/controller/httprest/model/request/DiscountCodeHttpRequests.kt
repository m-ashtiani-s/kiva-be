package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.application.usecase.discount.DiscountCodeFields
import kotlinx.serialization.Serializable

/**
 * Contract schema `DiscountCodeUpsertRequest`, the body of `POST` and `PUT /admin/discount-codes*`. Every field nullable
 * with a default, so one left out is its contract default or, for the required four, reported with every other problem.
 * [startsAt] and [endsAt] are read as text, so a malformed one is 400 DATE_TIME_INVALID on its field, not an unreadable body.
 */
@Serializable
data class DiscountCodeUpsertHttpRequest(
    val code: String? = null,
    val type: String? = null,
    val value: Long? = null,
    val label: String? = null,
    val minSubtotal: Long? = null,
    val maxDiscount: Long? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val usageLimit: Int? = null,
    val perUserLimit: Int? = null,
    val firstOrderOnly: Boolean? = null,
    val requiresLogin: Boolean? = null,
    val excludeSaleItems: Boolean? = null,
    val categoryIds: List<Long>? = null,
    val productIds: List<Long>? = null,
    val isActive: Boolean? = null,
) {
    fun toFields() = DiscountCodeFields(
        code, type, value, label, minSubtotal, maxDiscount, startsAt, endsAt, usageLimit, perUserLimit, firstOrderOnly, requiresLogin, excludeSaleItems,
        categoryIds, productIds, isActive,
    )
}
