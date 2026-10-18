package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.RefundDto
import kotlinx.serialization.Serializable

/**
 * Contract schema `Refund`, every field always. [referenceId] is the bank's reference and the notes are personal: shown,
 * never logged ([toString]).
 */
@Serializable
data class RefundHttpResponse(
    val id: Long,
    val amount: Long,
    val method: String,
    val status: String,
    val reason: String?,
    val returnId: Long?,
    val createdAt: String,
    val expectedBy: String?,
    val completedAt: String?,
    val referenceId: String?,
    val statusNote: String?,
) {
    override fun toString(): String = "RefundHttpResponse(id=$id, amount=$amount, status=$status)"

    companion object {
        fun from(refund: RefundDto) = RefundHttpResponse(
            id = refund.id,
            amount = refund.amount.toman,
            method = refund.method.name,
            status = refund.status.name,
            reason = refund.reason,
            returnId = refund.returnId,
            createdAt = refund.createdOn.toString(),
            expectedBy = refund.expectedBy.toString(),
            completedAt = refund.completedOn?.toString(),
            referenceId = refund.referenceId,
            statusNote = refund.statusNote,
        )
    }
}
