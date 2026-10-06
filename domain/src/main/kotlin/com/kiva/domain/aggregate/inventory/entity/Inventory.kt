package com.kiva.domain.aggregate.inventory.entity

import com.kiva.common.event.EventSourceEntity
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.entity.event.InventoryDomainEvent
import com.kiva.domain.aggregate.inventory.entity.event.ReservationReleased
import com.kiva.domain.aggregate.inventory.entity.event.StockAdjusted
import com.kiva.domain.aggregate.inventory.entity.event.StockReserved
import com.kiva.domain.aggregate.inventory.entity.event.StockShipped
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentNote
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant
import kotlin.properties.Delegates

/**
 * The stock of one color of a product (one variant): how many bags are in the warehouse ([onHand]) and how many of
 * them are promised to paid orders that haven't shipped yet ([reserved]). Its events are the warehouse book.
 *
 * Its own aggregate, apart from Product, so a purchase never competes with an edit of the description for the next
 * event number.
 *
 * Rules it keeps by itself:
 * - [available] (on hand minus reserved) is never negative: nothing is taken away or promised that isn't there
 * - a hand-made change goes the way its reason allows: a delivery or a return only adds, damage only takes away
 * - an order reserves once; asking again for the same amount changes nothing
 * - giving a reservation back twice changes nothing the second time, and so does shipping an order twice
 * - only what an order reserved is shipped for it
 */
class Inventory private constructor() : EventSourceEntity<InventoryDomainEvent>() {

    // Set by apply(). Kotlin doesn't allow lateinit on value classes, so it uses Delegates.notNull().
    var variantId: VariantId by Delegates.notNull()
        private set

    /** Bags in the warehouse, reserved ones included. */
    var onHand: Int = 0
        private set

    private val reservationMap = mutableMapOf<OrderCode, Quantity>()
    private val shippedOrders = mutableSetOf<OrderCode>()

    /** What each paid order that hasn't shipped yet was promised, in the order they reserved. */
    val reservations: Map<OrderCode, Quantity> get() = reservationMap.toMap()

    /** Bags promised to paid orders that haven't shipped yet. */
    val reserved: Int get() = reservationMap.values.sumOf { it.value }

    /** Bags that can still be sold. */
    val available: Int get() = onHand - reserved

    /** Loading: rebuild from the stored events. */
    constructor(events: List<InventoryDomainEvent>) : this() {
        applyEventStream(events)
    }

    private constructor(variantId: VariantId) : this() {
        this.variantId = variantId
    }

    companion object {
        /**
         * The stock of a color nothing has been recorded for yet: none in the warehouse, nothing reserved. It records
         * no event, because nothing has happened; its first change starts its stream.
         */
        fun empty(variantId: VariantId): Inventory = Inventory(variantId)
    }

    // ============ by hand (staff, seeder) ============

    /** A change staff made by hand: a delivery, a count, damage, a return or a correction. */
    fun adjust(
        delta: StockDelta,
        reason: AdjustmentReason,
        note: AdjustmentNote?,
        by: UserId,
        now: Instant,
    ): Result<Inventory, AdjustFailure> {
        if (!reason.allows(delta)) return Result.failure(AdjustFailure.WrongDirection(reason))
        // Reserved bags are promised to someone: only what is still available can be taken away.
        if (available + delta.value < 0) return Result.failure(AdjustFailure.MoreThanAvailable(available))

        raiseEvent(StockAdjusted(variantId, nextSequence(), now, by, delta, reason, note))
        return Result.success(this)
    }

    // ============ for orders (called by the order flow, a later phase) ============

    /**
     * Promises [quantity] bags to a paid order. Asking again for the same order and amount changes nothing (a payment
     * confirmed twice); a different amount, or an order that already shipped, is refused.
     */
    fun reserve(orderCode: OrderCode, quantity: Quantity, by: UserId, now: Instant): Result<Inventory, ReserveFailure> {
        if (orderCode in shippedOrders) return Result.failure(ReserveFailure.OrderAlreadyShipped(orderCode))
        reservationMap[orderCode]?.let { reservedBefore ->
            return if (reservedBefore == quantity) {
                Result.success(this)
            } else {
                Result.failure(ReserveFailure.ReservedWithAnotherQuantity(orderCode, reservedBefore))
            }
        }
        if (quantity.value > available) return Result.failure(ReserveFailure.MoreThanAvailable(available))

        raiseEvent(StockReserved(variantId, nextSequence(), now, by, orderCode, quantity))
        return Result.success(this)
    }

    /**
     * Gives back what an order reserved (cancelled, or not paid in time). An order with nothing reserved here (given
     * back already, shipped, or never reserved) changes nothing, so a cancellation that runs twice is harmless.
     */
    fun releaseReservation(orderCode: OrderCode, by: UserId, now: Instant): Result<Inventory, Nothing> {
        val quantity = reservationMap[orderCode] ?: return Result.success(this)

        raiseEvent(ReservationReleased(variantId, nextSequence(), now, by, orderCode, quantity))
        return Result.success(this)
    }

    /** What an order reserved left with the courier. Shipping it again changes nothing. */
    fun ship(orderCode: OrderCode, by: UserId, now: Instant): Result<Inventory, ShipFailure> {
        if (orderCode in shippedOrders) return Result.success(this)
        val quantity = reservationMap[orderCode] ?: return Result.failure(ShipFailure.NothingReserved(orderCode))

        raiseEvent(StockShipped(variantId, nextSequence(), now, by, orderCode, quantity))
        return Result.success(this)
    }

    override fun apply(event: InventoryDomainEvent) {
        variantId = event.variantId
        when (event) {
            is StockAdjusted -> onHand += event.delta.value
            is StockReserved -> reservationMap[event.orderCode] = event.quantity
            is ReservationReleased -> reservationMap.remove(event.orderCode)
            is StockShipped -> {
                reservationMap.remove(event.orderCode)
                shippedOrders += event.orderCode
                onHand -= event.quantity.value
            }
        }
        // Second wall: the methods above never allow this, so a stream that breaks it is broken data. Stop loudly.
        check(available >= 0) { "Broken stock stream of variant ${variantId.value}: on hand $onHand, reserved $reserved" }
    }

    override fun equals(other: Any?): Boolean = other is Inventory && other.variantId == variantId

    override fun hashCode(): Int = variantId.hashCode()

    override fun toString(): String = "Inventory(variantId=${variantId.value})"

    // ============ failures, one sealed family per operation ============

    sealed class AdjustFailure(code: String, kind: FailureKind, meta: Map<String, Any>) :
        ResultFailure(code, kind, field = "delta", meta = meta) {
        /** A delivery that takes bags away, damage that adds some, ... */
        data class WrongDirection(val reason: AdjustmentReason) :
            AdjustFailure("ADJUSTMENT_DIRECTION_INVALID", FailureKind.INVALID, mapOf("reason" to reason.name))

        data class MoreThanAvailable(val availableQuantity: Int) :
            AdjustFailure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, mapOf("availableQuantity" to availableQuantity))
    }

    sealed class ReserveFailure(code: String, kind: FailureKind, meta: Map<String, Any>) : ResultFailure(code, kind, meta = meta) {
        data class MoreThanAvailable(val availableQuantity: Int) :
            ReserveFailure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, mapOf("availableQuantity" to availableQuantity))

        data class ReservedWithAnotherQuantity(val orderCode: OrderCode, val reservedQuantity: Quantity) :
            ReserveFailure(
                "RESERVATION_QUANTITY_MISMATCH",
                FailureKind.CONFLICT,
                mapOf("orderCode" to orderCode.value, "reservedQuantity" to reservedQuantity.value),
            )

        data class OrderAlreadyShipped(val orderCode: OrderCode) :
            ReserveFailure("ORDER_ALREADY_SHIPPED", FailureKind.CONFLICT, mapOf("orderCode" to orderCode.value))
    }

    sealed class ShipFailure(code: String, kind: FailureKind, meta: Map<String, Any>) : ResultFailure(code, kind, meta = meta) {
        data class NothingReserved(val orderCode: OrderCode) :
            ShipFailure("RESERVATION_NOT_FOUND", FailureKind.BUSINESS_RULE, mapOf("orderCode" to orderCode.value))
    }
}
