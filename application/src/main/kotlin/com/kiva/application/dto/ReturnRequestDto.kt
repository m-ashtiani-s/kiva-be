package com.kiva.application.dto

import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * A return as the customer sees it (the contract's ReturnRequest; roadmap section 12.2, decisions 22 and 28): everything
 * but what the check of the parcel found. Without the Persian words: the status's label and the default instructions are
 * the HTTP layer's (ReturnTexts).
 *
 * Personal: the customer's [description] and staff's texts never go into a log ([toString]).
 *
 * @property items the lines going back, named and colored as they were bought (the order's lines), not as the catalog
 *   has them today.
 * @property instructions staff's own words; null shows the default text for [shippingPaidBy] once [showsInstructions].
 * @property refund the latest refund of this return, a FAILED one too (with the note the customer sees); null before any.
 */
data class ReturnRequestDto(
    val id: Long,
    val code: String,
    val orderCode: String,
    val status: ReturnStatus,
    val reason: ReturnReason,
    val description: String?,
    val items: List<Item>,
    val media: List<UploadedMediaDto>,
    val shippingPaidBy: ReturnShippingPayer,
    val instructions: String?,
    val refundMethod: RefundMethod,
    val rejectionReason: String?,
    val pickup: Pickup?,
    val refund: RefundDto?,
    val createdOn: Instant,
    val decidedOn: Instant?,
    val receivedOn: Instant?,
    val closedOn: Instant?,
    val closeReason: String?,
) {
    /** How to send it back means something only once it was approved: never while REQUESTED, nor once REJECTED (decision 28). */
    val showsInstructions: Boolean get() = status != ReturnStatus.REQUESTED && status != ReturnStatus.REJECTED

    override fun toString(): String = "ReturnRequestDto(id=$id, code=$code, status=$status)"

    /** One line going back, as bought. */
    data class Item(val lineId: Int, val name: String, val color: OrderDetailDto.Color, val quantity: Int)

    /** The shop collects it; [carrierName] as the shop's settings call it now. [note] is personal. */
    data class Pickup(val carrier: ShippingMethodCode, val carrierName: String, val scheduledFor: Instant?, val trackingCode: String?, val note: String?) {
        override fun toString(): String = "Pickup(carrier=$carrier, scheduledFor=$scheduledFor)"
    }
}
