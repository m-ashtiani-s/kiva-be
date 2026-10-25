package com.kiva.application.dto

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import java.time.Instant

/**
 * A refund as the panel and the customer see it (the contract's Refund). [referenceId] is the bank's reference: shown,
 * never logged ([toString] hides it and the notes).
 */
data class RefundDto(
    val id: Long,
    val amount: Money,
    val method: RefundMethod,
    val status: RefundStatus,
    val reason: String?,
    val returnId: Long?,
    val createdOn: Instant,
    val expectedBy: Instant,
    val completedOn: Instant?,
    val referenceId: String?,
    val statusNote: String?,
) {
    override fun toString(): String = "RefundDto(id=$id, amount=$amount, status=$status)"

    companion object {
        fun from(refund: Refund) = RefundDto(
            id = refund.id.value,
            amount = refund.amount,
            method = refund.method,
            status = refund.status,
            reason = refund.reason?.value,
            returnId = refund.returnId,
            createdOn = refund.createdOn,
            expectedBy = refund.expectedBy,
            completedOn = refund.completedOn,
            referenceId = refund.referenceId?.value,
            statusNote = refund.statusNote?.value,
        )
    }
}
