package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.ReturnRequestDto
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/**
 * Contract schema `ReturnRequest`: a return as the customer sees it, every field always there (decision 30). The status's
 * label and, once it was approved, the default instructions for whoever pays the way back are ReturnTexts'.
 * [description] and staff's texts are personal: [toString] shows none of them.
 */
@Serializable
data class ReturnRequestHttpResponse(
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
) {
    override fun toString(): String = "ReturnRequestHttpResponse(id=$id, code=$code, status=$status)"

    companion object {
        fun from(ret: ReturnRequestDto, urls: MediaUrls) = ReturnRequestHttpResponse(
            id = ret.id,
            code = ret.code,
            orderCode = ret.orderCode,
            status = ret.status.name,
            statusLabel = ReturnTexts.statusLabel(ret.status),
            reason = ret.reason.name,
            description = ret.description,
            items = ret.items.map { item ->
                ReturnItemHttpResponse(item.lineId, item.name, item.color.let { ColorHttpResponse(it.key, it.name, it.hex, it.isLight, it.sortOrder) }, item.quantity)
            },
            media = ret.media.map { MediaAssetHttpResponse.from(it, urls) },
            shippingPaidBy = ret.shippingPaidBy.name,
            instructions = if (ret.showsInstructions) ret.instructions ?: ReturnTexts.defaultInstructions(ret.shippingPaidBy) else null,
            refundMethod = ret.refundMethod.name,
            rejectionReason = ret.rejectionReason,
            pickup = ret.pickup?.let { ReturnPickupHttpResponse(it.carrier.name, it.carrierName, it.scheduledFor?.toString(), it.trackingCode, it.note) },
            refund = ret.refund?.let(RefundHttpResponse::from),
            createdAt = ret.createdOn.toString(),
            decidedAt = ret.decidedOn?.toString(),
            receivedAt = ret.receivedOn?.toString(),
            closedAt = ret.closedOn?.toString(),
            closeReason = ret.closeReason,
        )
    }
}

/** One line going back, named and colored as bought. */
@Serializable
data class ReturnItemHttpResponse(val orderItemId: Int, val name: String, val color: ColorHttpResponse, val quantity: Int)

/** The shop collects the parcel. */
@Serializable
data class ReturnPickupHttpResponse(val carrier: String, val carrierName: String, val scheduledFor: String?, val trackingCode: String?, val note: String?) {
    override fun toString(): String = "ReturnPickupHttpResponse(carrier=$carrier, scheduledFor=$scheduledFor)"
}
