package com.kiva.application.usecase.order

import com.kiva.application.dto.OrderDetailDto
import com.kiva.application.dto.OrderSummaryDto
import com.kiva.application.dto.PreShipmentMediaDto
import com.kiva.application.repository.OrderQm
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import java.time.Instant

/**
 * One order's page as the customer sees it (the contract's OrderDetail), built the same way for «سفارش‌های من» and for the
 * panel, which shows the same page and more (Rule 15). From the read model and the attempts' table: a page never reads
 * the event store. Runs inside the caller's transaction.
 */
class OrderDetails(
    private val paymentRepository: PaymentRepository,
    private val colorRepository: ColorRepository,
    private val reservations: Reservations,
    private val mediaRepository: MediaRepository,
) {

    /** [order]'s page at [now], with the shop's [settings] of now (the names of its shipping method and gateway). */
    fun of(order: OrderQm, settings: ShopSettings, now: Instant): OrderDetailDto = of(order, settings, now, paymentRepository.findAttemptsOf(order.orderId))

    /** The same, with [attempts] (every attempt of the order, oldest first) already read. */
    fun of(order: OrderQm, settings: ShopSettings, now: Instant, attempts: List<Payment>): OrderDetailDto {
        val palette = colorRepository.findAll().associateBy { it.key }
        val latest = attempts.lastOrNull()
        val media = mediaOf(listOf(order))
        return OrderDetailDto(
            summary = OrderSummaryDto.of(order, settings, now, reservations.infosOf(listOf(order), now)[order.orderId], media),
            items = order.lines.map { line ->
                val color = line.item.color
                // A color taken out of the palette since keeps its name and hex, as bought, and sorts last.
                val inPalette = palette[color.key]
                OrderDetailDto.Item(
                    lineId = line.lineId,
                    productId = line.productId.value,
                    variantId = line.variantId.value,
                    sku = line.item.sku.value,
                    name = line.item.name.value,
                    slug = line.item.slug.value,
                    color = OrderDetailDto.Color(color.key.value, color.name, color.hex, inPalette?.isLight ?: false, inPalette?.sortOrder ?: Int.MAX_VALUE),
                    imageUrl = line.item.imageUrl,
                    quantity = line.quantity.value,
                    unitPrice = line.priceTag.price,
                    unitCompareAtPrice = line.priceTag.compareAtPrice,
                    lineTotal = line.totals.total,
                )
            },
            address = order.address.let {
                OrderDetailDto.Address(
                    it.provinceName, it.cityName, it.line.value, it.plaque?.value, it.unit?.value, it.postalCode.value, it.recipientName.value,
                    it.recipientPhone.value,
                )
            },
            totals = order.totals,
            payment = latest?.let { paymentOf(it, settings) },
            messenger = order.messenger.let { OrderDetailDto.Messenger(it.channel, it.phone.value, it.username, it.note) },
            photos = PreShipmentMediaDto.of(order, media),
            timeline = order.timeline.map { OrderDetailDto.TimelineEntry(it.kind, it.status, it.at, it.failureReason, it.note, it.amount) },
            customerNote = order.customerNote?.value,
            gift = order.gift?.let { OrderDetailDto.Gift(it.message, it.hidePrices) },
        )
    }

    /** The uploads of [orders]' photos, sent and drafted, in one read. */
    fun mediaOf(orders: List<OrderQm>): Map<MediaId, MediaAsset> {
        val ids = orders.flatMap { it.photosSent + it.photoDraft }.map { it.mediaId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return mediaRepository.findAll(ids).associateBy { it.id }
    }

    /** One attempt to pay, as the order's page shows it; its gateway named as the shop's [settings] call it now. */
    fun paymentOf(payment: Payment, settings: ShopSettings): OrderDetailDto.Payment = OrderDetailDto.Payment(
        gateway = payment.gateway,
        gatewayName = settings.paymentGateways.firstOrNull { it.code == payment.gateway }?.name ?: payment.gateway.name,
        outcome = payment.status.outcome,
        refId = payment.refId?.value,
        paidOn = payment.settledOn.takeIf { payment.status == PaymentStatus.SUCCEEDED || payment.status == PaymentStatus.REFUNDED },
        cardMask = payment.cardMask?.value,
    )
}
