package com.kiva.application.dto

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.model.DiscountUsageStats

/**
 * A discount code as the panel shows it (the contract's DiscountCode): the code with its terms, and how much it was used
 * by orders paid and not given back.
 */
data class DiscountCodeDto(val code: DiscountCode, val usedCount: Int, val totalDiscountGiven: Money) {
    override fun toString(): String = "DiscountCodeDto(id=${code.id.value}, usedCount=$usedCount)"

    companion object {
        fun from(code: DiscountCode, usage: DiscountUsageStats?) =
            DiscountCodeDto(code, usage?.usedCount ?: 0, usage?.totalDiscountGiven ?: Money.constructFromPersisted(0))
    }
}
