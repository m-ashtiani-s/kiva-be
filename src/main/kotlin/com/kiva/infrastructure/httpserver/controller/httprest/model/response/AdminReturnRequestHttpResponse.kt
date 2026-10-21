package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AdminReturnRequestDto
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/**
 * Contract schema `AdminReturnRequest`: every field of the customer's ReturnRequest ([ReturnRequestHttpResponse]) and the
 * panel's own: the customer, what the check of the parcel found, where it may go next (in the order of its steps) and
 * the money its receipt suggests giving back. Every field always there. Personal and internal texts: [toString] shows
 * none of them.
 */
@Serializable
data class AdminReturnRequestHttpResponse(
    // ---- ReturnRequest ----
    val id: Long,
    val code: String,
    val orderCode: String,
    val status: String,
    val statusLabel: String,
    val reason: String,
    val description: String?,
    val items: List<ReturnItemHttpResponse>,
    val media: List<MediaAssetHttpResponse>,
    val shippingPaidBy: String,
    val instructions: String?,
    val refundMethod: String,
    val rejectionReason: String?,
    val pickup: ReturnPickupHttpResponse?,
    val refund: RefundHttpResponse?,
    val createdAt: String,
    val decidedAt: String?,
    val receivedAt: String?,
    val closedAt: String?,
    val closeReason: String?,
    // ---- AdminReturnRequest ----
    val customer: AdminReturnCustomerHttpResponse,
    val receipt: AdminReturnReceiptHttpResponse?,
    val allowedTransitions: List<String>,
    val suggestedRefund: Long?,
) {
    override fun toString(): String = "AdminReturnRequestHttpResponse(id=$id, code=$code, status=$status)"

    companion object {
        fun from(ret: AdminReturnRequestDto, urls: MediaUrls): AdminReturnRequestHttpResponse {
            val seen = ReturnRequestHttpResponse.from(ret.ret, urls)
            return AdminReturnRequestHttpResponse(
                id = seen.id,
                code = seen.code,
                orderCode = seen.orderCode,
                status = seen.status,
                statusLabel = seen.statusLabel,
                reason = seen.reason,
                description = seen.description,
                items = seen.items,
                media = seen.media,
                shippingPaidBy = seen.shippingPaidBy,
                instructions = seen.instructions,
                refundMethod = seen.refundMethod,
                rejectionReason = seen.rejectionReason,
                pickup = seen.pickup,
                refund = seen.refund,
                createdAt = seen.createdAt,
                decidedAt = seen.decidedAt,
                receivedAt = seen.receivedAt,
                closedAt = seen.closedAt,
                closeReason = seen.closeReason,
                customer = ret.customer.let { AdminReturnCustomerHttpResponse(it.id, it.fullName, it.phone) },
                receipt = ret.receipt?.let { receipt ->
                    AdminReturnReceiptHttpResponse(receipt.lines.map { AdminReturnReceiptItemHttpResponse(it.lineId, it.received, it.restock) }, receipt.note)
                },
                allowedTransitions = ret.allowedTransitions.sortedBy { it.ordinal }.map { it.name },
                suggestedRefund = ret.suggestedRefund?.toman,
            )
        }
    }
}

/** The return's customer (`AdminReturnRequest.customer`), as their account is now. Personal data: [toString] shows the id only. */
@Serializable
data class AdminReturnCustomerHttpResponse(val id: Long, val fullName: String?, val phone: String) {
    override fun toString(): String = "AdminReturnCustomerHttpResponse(id=$id)"
}

/** What the check of the parcel found (`AdminReturnRequest.receipt`); [note] is internal. */
@Serializable
data class AdminReturnReceiptHttpResponse(val items: List<AdminReturnReceiptItemHttpResponse>, val note: String?) {
    override fun toString(): String = "AdminReturnReceiptHttpResponse(items=$items)"
}

/** What came back of one line, and how many of them went back on the shelf. */
@Serializable
data class AdminReturnReceiptItemHttpResponse(val orderItemId: Int, val receivedQuantity: Int, val restockQuantity: Int)
