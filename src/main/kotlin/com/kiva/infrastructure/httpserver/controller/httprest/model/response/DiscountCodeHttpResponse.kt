package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.DiscountCodeDto
import kotlinx.serialization.Serializable

/** Contract schema `DiscountCode`: the code's terms as stored (its login switch the one that counts) and its uses. */
@Serializable
data class DiscountCodeHttpResponse(
    val id: Long,
    val code: String,
    val type: String,
    val value: Int,
    val label: String,
    val minSubtotal: Long?,
    val maxDiscount: Long?,
    val startsAt: String?,
    val endsAt: String?,
    val usageLimit: Int?,
    val perUserLimit: Int?,
    val firstOrderOnly: Boolean,
    val requiresLogin: Boolean,
    val excludeSaleItems: Boolean,
    val categoryIds: List<Long>,
    val productIds: List<Long>,
    val isActive: Boolean,
    val usedCount: Int,
    val totalDiscountGiven: Long,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        fun from(dto: DiscountCodeDto): DiscountCodeHttpResponse {
            val code = dto.code
            return DiscountCodeHttpResponse(
                id = code.id.value,
                code = code.code.value,
                type = code.type.name,
                value = code.value,
                label = code.label,
                minSubtotal = code.minSubtotal?.toman,
                maxDiscount = code.maxDiscount?.toman,
                startsAt = code.startsAt?.toString(),
                endsAt = code.endsAt?.toString(),
                usageLimit = code.usageLimit,
                perUserLimit = code.perUserLimit,
                firstOrderOnly = code.firstOrderOnly,
                requiresLogin = code.needsCustomer,
                excludeSaleItems = code.excludeSaleItems,
                categoryIds = code.categoryIds.map { it.value }.sorted(),
                productIds = code.productIds.map { it.value }.sorted(),
                isActive = code.isActive,
                usedCount = dto.usedCount,
                totalDiscountGiven = dto.totalDiscountGiven.toman,
                createdAt = code.createdOn.toString(),
                updatedAt = code.updatedOn.toString(),
            )
        }
    }
}
