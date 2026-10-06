package com.kiva.domain.aggregate.inventory.entity

import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.inventory.InventoryTestData.delta
import com.kiva.domain.aggregate.inventory.InventoryTestData.lilac
import com.kiva.domain.aggregate.inventory.InventoryTestData.now
import com.kiva.domain.aggregate.inventory.InventoryTestData.order
import com.kiva.domain.aggregate.inventory.InventoryTestData.quantity
import com.kiva.domain.aggregate.inventory.InventoryTestData.snapshot
import com.kiva.domain.aggregate.inventory.InventoryTestData.staff
import com.kiva.domain.aggregate.inventory.InventoryTestData.stocked
import com.kiva.domain.aggregate.inventory.entity.event.StockAdjusted
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentNote
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.DAMAGED
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.MANUAL_CORRECTION
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.RESTOCK
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.STOCKTAKE
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InventoryTest {

    private fun Inventory.eventTypes() = getRecordedEvents().map { it.type.value }

    @Nested
    inner class Starting {
        @Test
        fun `a color nothing was recorded for has nothing in stock, and records nothing`() {
            val inventory = Inventory.empty(lilac)

            assertEquals(listOf(lilac, 0, 0, 0, emptyMap<Any, Any>()), inventory.snapshot())
            assertEquals(emptyList(), inventory.getRecordedEvents())
        }
    }

    @Nested
    inner class AdjustingByHand {
        @Test
        fun `a delivery adds and damage takes away, one event each, numbered 1 and 2`() {
            val note = AdjustmentNote.constructFromPersisted("دسته‌ش پاره شده")
            val inventory = stocked(10)

            inventory.adjust(delta(-2), DAMAGED, note, staff, now)

            assertEquals(8, inventory.onHand)
            assertEquals(8, inventory.available)
            assertEquals(listOf(1L, 2L), inventory.getRecordedEvents().map { it.sequence.value })
            val damage = inventory.getRecordedEvents().last() as StockAdjusted
            assertEquals(listOf(lilac, delta(-2), DAMAGED, note), listOf(damage.variantId, damage.delta, damage.reason, damage.note))
        }

        @Test
        fun `a count or a correction can go either way`() {
            val inventory = stocked(10)

            inventory.adjust(delta(-3), STOCKTAKE, null, staff, now)
            inventory.adjust(delta(2), STOCKTAKE, null, staff, now)
            inventory.adjust(delta(-1), MANUAL_CORRECTION, null, staff, now)
            inventory.adjust(delta(4), MANUAL_CORRECTION, null, staff, now)

            assertEquals(12, inventory.onHand)
        }

        @ParameterizedTest
        @CsvSource("RESTOCK, -1", "RETURNED, -1", "DAMAGED, 1")
        fun `each reason allows only its own direction, as an input problem on delta`(reason: AdjustmentReason, change: Int) {
            val inventory = stocked(10)

            val result = inventory.adjust(delta(change), reason, null, staff, now)

            assertEquals(Result.failure(Inventory.AdjustFailure.WrongDirection(reason)), result)
            assertEquals("ADJUSTMENT_DIRECTION_INVALID", result.failureOrNull()?.code)
            assertEquals(FailureKind.INVALID, result.failureOrNull()?.kind)
            assertEquals("delta", result.failureOrNull()?.field)
            assertEquals(10, inventory.onHand)
        }

        @Test
        fun `taking away more than there is is QUANTITY_EXCEEDS_STOCK with how many are there, a 422`() {
            val inventory = stocked(3)

            val result = inventory.adjust(delta(-4), DAMAGED, null, staff, now)

            assertEquals(Result.failure(Inventory.AdjustFailure.MoreThanAvailable(3)), result)
            assertEquals(FailureKind.BUSINESS_RULE, result.failureOrNull()?.kind)
            assertEquals(mapOf<String, Any>("availableQuantity" to 3), result.failureOrNull()?.meta)
            assertEquals(listOf("StockAdjusted"), inventory.eventTypes())
        }

        @Test
        fun `bags promised to an order can't be taken away by hand, only the rest`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(3), staff, now)

            assertEquals(Result.failure(Inventory.AdjustFailure.MoreThanAvailable(2)), inventory.adjust(delta(-3), DAMAGED, null, staff, now))
            assertTrue(inventory.adjust(delta(-2), DAMAGED, null, staff, now).isSuccess)
            assertEquals(listOf(3, 3, 0), listOf(inventory.onHand, inventory.reserved, inventory.available))
        }
    }

    @Nested
    inner class Reserving {
        @Test
        fun `a reservation takes from what can be sold, the bags stay in the warehouse`() {
            val inventory = stocked(5)

            inventory.reserve(order(1), quantity(2), staff, now)

            assertEquals(listOf(5, 2, 3), listOf(inventory.onHand, inventory.reserved, inventory.available))
            assertEquals(mapOf(order(1) to quantity(2)), inventory.reservations)
        }

        @Test
        fun `more than can be sold is QUANTITY_EXCEEDS_STOCK with how many can`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(4), staff, now)

            val result = inventory.reserve(order(2), quantity(2), staff, now)

            assertEquals(Result.failure(Inventory.ReserveFailure.MoreThanAvailable(1)), result)
            assertEquals(mapOf<String, Any>("availableQuantity" to 1), result.failureOrNull()?.meta)
        }

        @Test
        fun `the same order asking again for the same amount changes nothing and records nothing`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(2), staff, now)

            assertTrue(inventory.reserve(order(1), quantity(2), staff, now).isSuccess)
            assertEquals(2, inventory.reserved)
            assertEquals(listOf("StockAdjusted", "StockReserved"), inventory.eventTypes())
        }

        @Test
        fun `the same order with another amount is a conflict`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(2), staff, now)

            val result = inventory.reserve(order(1), quantity(3), staff, now)

            assertEquals(Result.failure(Inventory.ReserveFailure.ReservedWithAnotherQuantity(order(1), quantity(2))), result)
            assertEquals(FailureKind.CONFLICT, result.failureOrNull()?.kind)
        }

        @Test
        fun `an order that already shipped can't reserve again`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(2), staff, now)
            inventory.ship(order(1), staff, now)

            assertEquals(Result.failure(Inventory.ReserveFailure.OrderAlreadyShipped(order(1))), inventory.reserve(order(1), quantity(2), staff, now))
        }
    }

    @Nested
    inner class GivingBack {
        @Test
        fun `a reservation given back can be sold again`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(2), staff, now)

            inventory.releaseReservation(order(1), staff, now)

            assertEquals(listOf(5, 0, 5), listOf(inventory.onHand, inventory.reserved, inventory.available))
        }

        @Test
        fun `giving it back a second time changes nothing and records nothing`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(2), staff, now)
            inventory.reserve(order(2), quantity(1), staff, now)
            inventory.releaseReservation(order(1), staff, now)

            assertTrue(inventory.releaseReservation(order(1), staff, now).isSuccess)

            assertEquals(listOf(5, 1, 4), listOf(inventory.onHand, inventory.reserved, inventory.available))
            assertEquals(listOf("StockAdjusted", "StockReserved", "StockReserved", "ReservationReleased"), inventory.eventTypes())
        }

        @Test
        fun `an order that reserved nothing here, or already shipped, has nothing to give back`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(2), staff, now)
            inventory.ship(order(1), staff, now)

            inventory.releaseReservation(order(1), staff, now)
            inventory.releaseReservation(order(9), staff, now)

            assertEquals(3, inventory.onHand)
            assertEquals(listOf("StockAdjusted", "StockReserved", "StockShipped"), inventory.eventTypes())
        }
    }

    @Nested
    inner class Shipping {
        @Test
        fun `what the order reserved leaves the warehouse and the reserved count`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(2), staff, now)

            inventory.ship(order(1), staff, now)

            assertEquals(listOf(3, 0, 3), listOf(inventory.onHand, inventory.reserved, inventory.available))
        }

        @Test
        fun `shipping the same order again changes nothing`() {
            val inventory = stocked(5)
            inventory.reserve(order(1), quantity(2), staff, now)
            inventory.ship(order(1), staff, now)

            assertTrue(inventory.ship(order(1), staff, now).isSuccess)
            assertEquals(3, inventory.onHand)
            assertEquals(3, inventory.getRecordedEvents().size)
        }

        @Test
        fun `an order that reserved nothing here can't ship from here`() {
            val result = stocked(5).ship(order(1), staff, now)

            assertEquals(Result.failure(Inventory.ShipFailure.NothingReserved(order(1))), result)
            assertEquals("RESERVATION_NOT_FOUND", result.failureOrNull()?.code)
        }
    }

    @Nested
    inner class Replay {
        @Test
        fun `the book rebuilds exactly the same stock, shipped orders included`() {
            val original = stocked(10)
            original.reserve(order(1), quantity(2), staff, now)
            original.reserve(order(2), quantity(3), staff, now)
            original.ship(order(1), staff, now)
            original.adjust(delta(-1), DAMAGED, null, staff, now)

            val loaded = Inventory(original.getRecordedEvents())

            assertEquals(original.snapshot(), loaded.snapshot())
            assertEquals(emptyList(), loaded.getRecordedEvents())
            // It also remembers that order 1 shipped.
            assertEquals(Result.failure(Inventory.ReserveFailure.OrderAlreadyShipped(order(1))), loaded.reserve(order(1), quantity(2), staff, now))
        }

        @Test
        fun `a loaded stock goes on numbering after its last event`() {
            val loaded = Inventory(stocked(5).getRecordedEvents())

            loaded.adjust(delta(1), RESTOCK, null, staff, now)

            assertEquals(listOf(2L), loaded.getRecordedEvents().map { it.sequence.value })
        }

        @Test
        fun `a stream that takes away bags that were never there is broken data and stops`() {
            val broken = StockAdjusted(lilac, EventSequence.FIRST, now, staff, delta(-3), DAMAGED, null)

            assertFailsWith<IllegalStateException> { Inventory(listOf(broken)) }
        }
    }

    /**
     * The step's main rule: whatever happens, in whatever order, nothing that isn't there is sold. Hundreds of random
     * changes, the refused ones included; after each one the numbers must still add up.
     */
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    fun `whatever happens in whatever order, available never goes below zero`(seed: Int) {
        val random = Random(seed)
        val inventory = Inventory.empty(lilac)

        repeat(500) {
            val someOrder = order(random.nextInt(1, 60))
            when (random.nextInt(4)) {
                0 -> {
                    val change = random.nextInt(-15, 15).let { if (it == 0) 1 else it }
                    inventory.adjust(delta(change), AdjustmentReason.entries.random(random), null, staff, now)
                }
                1 -> inventory.reserve(someOrder, quantity(random.nextInt(1, 8)), staff, now)
                2 -> inventory.releaseReservation(someOrder, staff, now)
                else -> inventory.ship(someOrder, staff, now)
            }
            assertTrue(inventory.available >= 0, "available ${inventory.available} after step $it")
            assertTrue(inventory.reserved >= 0, "reserved ${inventory.reserved} after step $it")
        }

        assertEquals(inventory.snapshot(), Inventory(inventory.getRecordedEvents()).snapshot())
        assertTrue(inventory.getRecordedEvents().size > 100, "the walk did too little to prove anything")
    }
}
