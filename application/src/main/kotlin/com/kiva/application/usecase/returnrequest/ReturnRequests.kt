package com.kiva.application.usecase.returnrequest

import com.kiva.application.dto.OrderDetailDto
import com.kiva.application.dto.RefundDto
import com.kiva.application.dto.ReturnRequestDto
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.settings.entity.ShopSettings

/**
 * Returns as the customer sees them (the contract's ReturnRequest), built the same way for «مرجوعی‌های من», the order's
 * page and the panel (Rule 15). A whole page in a few reads, never one per return: its orders' lines (the names and colors
 * as bought), its media, its refunds. Runs inside the caller's transaction.
 */
class ReturnRequests(
    private val returnRequestRepository: ReturnRequestRepository,
    private val orderQmRepository: OrderQmRepository,
    private val mediaRepository: MediaRepository,
    private val refundRepository: RefundRepository,
    private val colorRepository: ColorRepository,
) {

    /** Every return of [order], newest first (REJECTED ones too), as its page shows them. */
    fun ofOrder(order: OrderQm, settings: ShopSettings): List<ReturnRequestDto> =
        dtosOf(returnRequestRepository.findOfOrder(order.orderId), mapOf(order.orderId to order), settings)

    /** [returns], in their order, with their orders read in one go. */
    fun of(returns: List<ReturnRequest>, settings: ShopSettings): List<ReturnRequestDto> {
        val orders = orderQmRepository.findByIds(returns.map { it.orderId }).associateBy { it.orderId }
        return dtosOf(returns, orders, settings)
    }

    /** The latest refund of each of [returns] (by return id), a FAILED one too: what the customer and the panel show. */
    fun latestRefunds(returns: List<ReturnRequest>): Map<Long, Refund> =
        refundRepository.findAllOfReturns(returns.map { it.id }).groupBy { checkNotNull(it.returnId) }.mapValues { (_, refunds) -> refunds.last() }

    private fun dtosOf(returns: List<ReturnRequest>, orders: Map<OrderId, OrderQm>, settings: ShopSettings): List<ReturnRequestDto> {
        if (returns.isEmpty()) return emptyList()
        val palette = colorRepository.findAll().associateBy { it.key }
        val mediaIds = returns.flatMap { it.mediaIds }.distinct()
        val media = if (mediaIds.isEmpty()) emptyMap() else mediaRepository.findAll(mediaIds).associateBy { it.id }
        val refunds = latestRefunds(returns)
        return returns.map { ret ->
            val order = orders[ret.orderId] ?: error("Return ${ret.id.value} of order ${ret.orderId.value}, which the read model doesn't have")
            val lines = order.lines.associateBy { it.lineId }
            ReturnRequestDto(
                id = ret.id.value,
                code = ret.code.value,
                orderCode = ret.orderCode.value,
                status = ret.status,
                reason = ret.reason,
                description = ret.description?.value,
                items = ret.items.map { item ->
                    val line = lines[item.lineId] ?: error("Return ${ret.id.value} of line ${item.lineId}, which order ${order.code.value} doesn't have")
                    val color = line.item.color
                    val inPalette = palette[color.key]
                    ReturnRequestDto.Item(
                        item.lineId, line.item.name.value,
                        OrderDetailDto.Color(color.key.value, color.name, color.hex, inPalette?.isLight ?: false, inPalette?.sortOrder ?: Int.MAX_VALUE),
                        item.quantity,
                    )
                },
                // An attached upload is never removed: one missing would be a bug of the clean-up, shown as nothing rather than a broken page.
                media = ret.mediaIds.mapNotNull { media[it] }.map(UploadedMediaDto::from),
                shippingPaidBy = ret.shippingPaidBy,
                instructions = ret.instructions?.value,
                refundMethod = ret.refundMethod,
                rejectionReason = ret.rejectionReason?.value,
                pickup = ret.pickup?.let { pickup ->
                    ReturnRequestDto.Pickup(
                        pickup.carrier, settings.shippingMethods.firstOrNull { it.code == pickup.carrier }?.name ?: pickup.carrier.name, pickup.scheduledFor,
                        pickup.trackingCode?.value, pickup.note?.value,
                    )
                },
                refund = refunds[ret.id.value]?.let(RefundDto::from),
                createdOn = ret.createdOn,
                decidedOn = ret.decidedOn,
                receivedOn = ret.receivedOn,
                closedOn = ret.closedOn,
                closeReason = ret.closeReason?.value,
            )
        }
    }
}
