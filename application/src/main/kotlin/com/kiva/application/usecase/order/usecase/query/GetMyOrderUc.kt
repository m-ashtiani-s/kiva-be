package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.dto.OrderDetailDto
import com.kiva.application.dto.OrderSummaryDto
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * One order's page (`GET /me/orders/{orderCode}`): its card, its lines, address, totals and notes as they were when it
 * was placed, its latest attempt to pay, and its timeline. From the read model and the attempts' table: a page never
 * reads the event store. The customer's own only: someone else's order, one that doesn't exist and a code that can't
 * be one are all ORDER_NOT_FOUND.
 */
class GetMyOrderUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val paymentRepository: PaymentRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val colorRepository: ColorRepository,
    private val reservations: Reservations,
    private val clock: Clock,
) : QueryUseCase<GetMyOrderUc.Qry, OrderDetailDto, GetMyOrderUc.Failure> {

    private val logger = getLogger()

    /**
     * @property userId from the access token, never from the request.
     * @property orderCode the path's code, as it came.
     */
    data class Qry(val userId: UserId, val orderCode: String)

    override suspend fun execute(query: Qry): Result<OrderDetailDto, Failure> {
        val code = OrderCode.makeNew(query.orderCode).getOrNull()
        // A code from the path may be anything: only one that can be a code is written to the log.
        logger.info("GetMyOrderUc.execute userId=${query.userId.value} orderCode=${code?.value ?: "(not a code)"}")
        if (code == null) return Result.failure(Failure.OrderNotFound)

        return transactionManager.inTransaction {
            val order = orderQmRepository.findOfCustomer(query.userId, code) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            val settings = shopSettingsRepository.get()
            Result.success(detailOf(order, settings))
        }
    }

    private fun detailOf(order: OrderQm, settings: ShopSettings): OrderDetailDto {
        val palette = colorRepository.findAll().associateBy { it.key }
        val latest = paymentRepository.findAttemptsOf(order.orderId).lastOrNull()
        return OrderDetailDto(
            summary = clock.instant().let { now -> OrderSummaryDto.of(order, settings, now, reservations.infosOf(listOf(order), now)[order.orderId]) },
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
            payment = latest?.let { payment ->
                OrderDetailDto.Payment(
                    gateway = payment.gateway,
                    gatewayName = settings.paymentGateways.firstOrNull { it.code == payment.gateway }?.name ?: payment.gateway.name,
                    outcome = payment.status.outcome,
                    refId = payment.refId?.value,
                    paidOn = payment.settledOn.takeIf { payment.status == PaymentStatus.SUCCEEDED || payment.status == PaymentStatus.REFUNDED },
                    cardMask = payment.cardMask?.value,
                )
            },
            messenger = OrderDetailDto.Messenger(order.messenger.channel, order.messenger.phone.value, order.messenger.note),
            timeline = order.timeline.map { OrderDetailDto.TimelineEntry(it.status, it.at, it.failureReason) },
            customerNote = order.customerNote?.value,
            gift = order.gift?.let { OrderDetailDto.Gift(it.message, it.hidePrices) },
        )
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
