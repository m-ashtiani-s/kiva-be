package com.kiva.infrastructure.persistence.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
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

/** One event of every kind of stock event, every field filled in, plus the one with an optional part left empty. */
object InventoryEventSamples {

    private val lilac = VariantId.constructFromPersisted(21)
    private val staff = UserId.constructFromPersisted(12)

    // Down to the nanosecond on purpose: the stored event must keep the exact moment.
    private val at = Instant.parse("2026-10-06T08:15:30.123456789Z")
    private val order = OrderCode.constructFromPersisted("KV-218340")

    private fun eventId(n: Int) = EventId.constructFromPersisted("7a1c9e2b-5d3f-4b8a-8c6d-%012d".format(n))
    private fun sequence(n: Long) = EventSequence.constructFromPersisted(n)

    val stockAdjusted = StockAdjusted(
        lilac, sequence(1), at, staff,
        delta = StockDelta.constructFromPersisted(12),
        reason = AdjustmentReason.RESTOCK,
        note = AdjustmentNote.constructFromPersisted("بارنامه \"۲۳۴\" از کارگاه"),
        id = eventId(1),
    )

    val stockReserved = StockReserved(lilac, sequence(2), at, UserId.SYSTEM, order, Quantity.constructFromPersisted(2), eventId(2))

    val onePerKind: List<InventoryDomainEvent> = listOf(
        stockAdjusted,
        stockReserved,
        ReservationReleased(lilac, sequence(3), at, UserId.SYSTEM, order, Quantity.constructFromPersisted(2), eventId(3)),
        StockShipped(lilac, sequence(4), at, staff, order, Quantity.constructFromPersisted(2), eventId(4)),
    )

    val nothingOptional: List<InventoryDomainEvent> = listOf(
        StockAdjusted(lilac, sequence(5), at, staff, StockDelta.constructFromPersisted(-3), AdjustmentReason.DAMAGED, null, eventId(5)),
    )
}
