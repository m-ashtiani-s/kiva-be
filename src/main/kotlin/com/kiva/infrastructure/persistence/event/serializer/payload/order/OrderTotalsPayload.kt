package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import kotlinx.serialization.Serializable

/**
 * [OrderTotals]: amounts in toman.
 *
 * Stored in the order's OrderPlaced event, and in the same shape in the order read model (orderqm_tbl), so both read
 * back through this class. Never renamed: see EventPayload.
 */
@Serializable
data class OrderTotalsPayload(
    val itemsCount: Int,
    val linesCount: Int,
    val itemsCompareAtTotal: Long,
    val productDiscount: Long,
    val subtotal: Long,
    val codeDiscount: Long,
    val shippingCost: Long,
    val shippingIsFree: Boolean,
    val shippingFreeReason: String?,
    val payable: Long,
) {
    constructor(totals: OrderTotals) : this(
        itemsCount = totals.itemsCount,
        linesCount = totals.linesCount,
        itemsCompareAtTotal = totals.itemsCompareAtTotal.toman,
        productDiscount = totals.productDiscount.toman,
        subtotal = totals.subtotal.toman,
        codeDiscount = totals.codeDiscount.toman,
        shippingCost = totals.shippingCost.toman,
        shippingIsFree = totals.shippingIsFree,
        shippingFreeReason = totals.shippingFreeReason?.name,
        payable = totals.payable.toman,
    )

    fun toDomain(): OrderTotals = OrderTotals.constructFromPersisted(
        itemsCount = itemsCount,
        linesCount = linesCount,
        itemsCompareAtTotal = Money.constructFromPersisted(itemsCompareAtTotal),
        productDiscount = Money.constructFromPersisted(productDiscount),
        subtotal = Money.constructFromPersisted(subtotal),
        codeDiscount = Money.constructFromPersisted(codeDiscount),
        shippingCost = Money.constructFromPersisted(shippingCost),
        shippingIsFree = shippingIsFree,
        shippingFreeReason = shippingFreeReason?.let { ShippingFreeReason.valueOf(it) },
        payable = Money.constructFromPersisted(payable),
    )
}
