package com.kiva.domain.aggregate.inventory

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.inventory.InventoryTestData.delta
import com.kiva.domain.aggregate.inventory.InventoryTestData.lilac
import com.kiva.domain.aggregate.inventory.InventoryTestData.now
import com.kiva.domain.aggregate.inventory.InventoryTestData.staff
import com.kiva.domain.aggregate.inventory.InventoryTestData.stocked
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.entity.event.InventoryDomainEvent
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.DAMAGED
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.RESTOCK
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.order.OrderFixtures
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An in-memory stand-in for the port: each color's whole stream, as the event store keeps it, and the last object saved. */
private class InMemoryInventories : InventoryRepository {
    val saved = mutableMapOf<VariantId, Inventory>()
    private val streams = mutableMapOf<VariantId, MutableList<InventoryDomainEvent>>()

    override fun get(variantId: VariantId): Inventory? = streams[variantId]?.let { Inventory(it.toList()) }

    override fun save(inventory: Inventory) {
        saved[inventory.variantId] = inventory
        streams.getOrPut(inventory.variantId) { mutableListOf() } += inventory.getRecordedEvents()
    }
}

class InventoryDomainServiceTest {

    private val inventories = InMemoryInventories()
    private val service = InventoryDomainService(inventories)

    @Nested
    inner class GettingStock {
        @Test
        fun `a color nothing was recorded for has an empty stock instead of none at all`() {
            val inventory = service.getInventory(lilac).getOrNull()!!

            assertEquals(listOf(lilac, 0), listOf(inventory.variantId, inventory.onHand))
        }

        @Test
        fun `a stored color comes back with its stock`() {
            inventories.save(stocked(7))

            assertEquals(7, service.getInventory(lilac).getOrNull()?.onHand)
        }
    }

    @Nested
    inner class AdjustingStock {
        @Test
        fun `the first change of a color starts its book at 1`() {
            val inventory = service.adjustStock(lilac, delta(12), RESTOCK, null, staff, now).getOrNull()!!

            assertEquals(12, inventory.onHand)
            assertEquals(listOf(1L), inventory.getRecordedEvents().map { it.sequence.value })
        }

        @Test
        fun `a stored color goes on after its last event`() {
            inventories.save(stocked(7))

            val inventory = service.adjustStock(lilac, delta(-2), DAMAGED, null, staff, now).getOrNull()!!

            assertEquals(5, inventory.onHand)
            assertEquals(listOf(2L), inventory.getRecordedEvents().map { it.sequence.value })
        }

        @Test
        fun `it never saves, because saving is the use case's job in its one transaction`() {
            service.adjustStock(lilac, delta(12), RESTOCK, null, staff, now)

            assertTrue(inventories.saved.isEmpty())
        }

        @Test
        fun `the aggregate's refusals come back as the service's own failures`() {
            assertEquals(
                Result.failure(InventoryDomainService.AdjustStockFailure.WrongDirection(DAMAGED)),
                service.adjustStock(lilac, delta(3), DAMAGED, null, staff, now),
            )
            assertEquals(
                Result.failure(InventoryDomainService.AdjustStockFailure.MoreThanAvailable(0)),
                service.adjustStock(lilac, delta(-1), DAMAGED, null, staff, now),
            )
        }
    }

    @Nested
    inner class ReservingForAnOrder {
        private val order = InventoryTestData.order(1)

        private fun stock(variant: Long, onHand: Int) {
            val inventory = Inventory.empty(VariantId.constructFromPersisted(variant))
            inventory.adjust(delta(onHand), RESTOCK, null, staff, now)
            inventories.save(inventory)
        }

        private fun line(lineId: Int, product: Long, variant: Long, quantity: Int): OrderLine = OrderLine.constructFromPersisted(
            lineId, ProductId.constructFromPersisted(product), VariantId.constructFromPersisted(variant), OrderFixtures.item(),
            InventoryTestData.quantity(quantity), PriceTag.constructFromPersisted(Money.constructFromPersisted(1_450_000), null),
            reservable = true, returnable = true,
        )

        @Test
        fun `every line is held, and the stocks come back in lock order, by product and then color, whatever the order's own order`() {
            stock(301, 5)
            stock(202, 5)
            stock(201, 5)

            val held = service.reserveForOrder(order, listOf(line(1, 30, 301, 2), line(2, 20, 202, 1), line(3, 20, 201, 3)), staff, now).getOrNull()!!

            assertEquals(listOf(201L, 202L, 301L), held.map { it.variantId.value })
            assertEquals(listOf(3, 1, 2), held.map { it.reservations.getValue(order).value })
            assertEquals(listOf(2, 4, 3), held.map { it.available })
        }

        @Test
        fun `all or nothing - a short color holds nothing, and the failure names every short color, one never stocked too`() {
            stock(201, 5)
            stock(202, 1)

            val failure = service.reserveForOrder(order, listOf(line(1, 20, 201, 2), line(2, 20, 202, 2), line(3, 30, 301, 1)), staff, now)

            assertEquals(
                Result.failure(InventoryDomainService.ReserveForOrderFailure.OutOfStock(listOf(VariantId.constructFromPersisted(202), VariantId.constructFromPersisted(301)))),
                failure,
            )
            assertEquals(listOf(5, 1), listOf(201L, 202L).map { service.getInventory(VariantId.constructFromPersisted(it)).getOrNull()!!.available })
        }

        @Test
        fun `it never saves`() {
            stock(201, 5)
            val before = inventories.saved.getValue(VariantId.constructFromPersisted(201)).getRecordedEvents().size

            service.reserveForOrder(order, listOf(line(1, 20, 201, 2)), staff, now)

            assertEquals(before, inventories.saved.getValue(VariantId.constructFromPersisted(201)).getRecordedEvents().size)
        }

        @Test
        fun `releasing gives every line back, in lock order, and a second release gives nothing back twice`() {
            stock(301, 5)
            stock(202, 5)
            stock(201, 5)
            val lines = listOf(line(1, 30, 301, 2), line(2, 20, 202, 1), line(3, 20, 201, 3))
            service.reserveForOrder(order, lines, staff, now).getOrNull()!!.forEach(inventories::save)

            val released = service.releaseForOrder(order, lines, staff, now)
            released.forEach(inventories::save)

            assertEquals(listOf(201L, 202L, 301L), released.map { it.variantId.value })
            assertEquals(listOf(5, 5, 5), listOf(201L, 202L, 301L).map { service.getInventory(VariantId.constructFromPersisted(it)).getOrNull()!!.available })
            assertEquals(emptyList(), service.releaseForOrder(order, lines, staff, now), "nothing is held for it any more")
        }

        @Test
        fun `releasing leaves out a color that holds nothing for the order, and one never stocked`() {
            stock(201, 5)
            stock(202, 5)
            service.reserveForOrder(order, listOf(line(1, 20, 201, 2)), staff, now).getOrNull()!!.forEach(inventories::save)

            val released = service.releaseForOrder(order, listOf(line(1, 20, 201, 2), line(2, 20, 202, 1), line(3, 30, 301, 1)), staff, now)

            assertEquals(listOf(201L), released.map { it.variantId.value })
        }

        @Test
        fun `shipping takes every line off the shelf, in lock order, and shipping again changes nothing`() {
            stock(301, 5)
            stock(202, 5)
            stock(201, 5)
            val lines = listOf(line(1, 30, 301, 2), line(2, 20, 202, 1), line(3, 20, 201, 3))
            service.reserveForOrder(order, lines, staff, now).getOrNull()!!.forEach(inventories::save)

            val shipped = service.shipForOrder(order, lines, staff, now)
            shipped.forEach(inventories::save)

            assertEquals(listOf(201L, 202L, 301L), shipped.map { it.variantId.value })
            val after = listOf(201L, 202L, 301L).map { service.getInventory(VariantId.constructFromPersisted(it)).getOrNull()!! }
            assertEquals(listOf(2, 4, 3), after.map { it.onHand })
            assertEquals(listOf(0, 0, 0), after.map { it.reserved })
            assertEquals(emptyList(), service.shipForOrder(order, lines, staff, now), "shipped already")
        }

        @Test
        fun `a group's orders ship together - one lock order across them, each color read once and shipped for each order on it`() {
            stock(201, 5)
            stock(202, 5)
            stock(301, 5)
            val other = InventoryTestData.order(2)
            val first = listOf(line(1, 30, 301, 1), line(2, 20, 201, 1))
            val second = listOf(line(1, 20, 201, 2), line(2, 20, 202, 1))
            service.reserveForOrder(order, first, staff, now).getOrNull()!!.forEach(inventories::save)
            service.reserveForOrder(other, second, staff, now).getOrNull()!!.forEach(inventories::save)

            val shipped = service.shipForOrders(mapOf(order to first, other to second), staff, now)
            shipped.forEach(inventories::save)

            assertEquals(listOf(201L, 202L, 301L), shipped.map { it.variantId.value })
            assertEquals(2, shipped.first().getRecordedEvents().size, "color 201 ships for both orders, read once")
            val after = listOf(201L, 202L, 301L).map { service.getInventory(VariantId.constructFromPersisted(it)).getOrNull()!! }
            assertEquals(listOf(2, 4, 4), after.map { it.onHand })
            assertEquals(listOf(0, 0, 0), after.map { it.reserved })
        }

        @Test
        fun `shipping a line that holds nothing for the order is a bug and stops loudly`() {
            stock(201, 5)

            assertThrows<IllegalStateException> { service.shipForOrder(order, listOf(line(1, 20, 201, 1)), staff, now) }
            assertThrows<IllegalStateException> { service.shipForOrder(order, listOf(line(1, 30, 301, 1)), staff, now) }
        }

        private fun available(variant: Long) = service.getInventory(VariantId.constructFromPersisted(variant)).getOrNull()!!.available

        private fun heldFor(variant: Long) = service.getInventory(VariantId.constructFromPersisted(variant)).getOrNull()!!.reservations[order]?.value

        @Test
        fun `a swapped color - the old one's hold goes back, the new one is held, in lock order`() {
            stock(301, 5)
            stock(202, 5)
            val before = listOf(line(1, 30, 301, 2))
            service.reserveForOrder(order, before, staff, now).getOrNull()!!.forEach(inventories::save)

            val moved = service.rebalanceForOrder(order, before, listOf(line(1, 20, 202, 2)), staff, now).getOrNull()!!
            moved.forEach(inventories::save)

            assertEquals(listOf(202L, 301L), moved.map { it.variantId.value })
            assertEquals(listOf(5, 3), listOf(available(301), available(202)))
            assertEquals(listOf(null, 2), listOf(heldFor(301), heldFor(202)))
        }

        @Test
        fun `swapping line 1 (color A) to color B that line 2 has already - B is held once with both quantities, A goes back`() {
            stock(201, 5)
            stock(202, 5)
            val before = listOf(line(1, 20, 201, 1), line(2, 20, 202, 2))
            service.reserveForOrder(order, before, staff, now).getOrNull()!!.forEach(inventories::save)

            service.rebalanceForOrder(order, before, listOf(line(1, 20, 202, 1), line(2, 20, 202, 2)), staff, now).getOrNull()!!.forEach(inventories::save)

            assertEquals(listOf(null, 3), listOf(heldFor(201), heldFor(202)))
            assertEquals(listOf(5, 2), listOf(available(201), available(202)))
        }

        @Test
        fun `a line taken out gives its hold back, and a color whose total didn't change isn't touched`() {
            stock(201, 5)
            stock(301, 5)
            val before = listOf(line(1, 30, 301, 1), line(2, 20, 201, 2))
            service.reserveForOrder(order, before, staff, now).getOrNull()!!.forEach(inventories::save)

            val moved = service.rebalanceForOrder(order, before, listOf(line(2, 20, 201, 2)), staff, now).getOrNull()!!

            assertEquals(listOf(301L), moved.map { it.variantId.value })
        }

        @Test
        fun `a new color with too few left is QUANTITY_EXCEEDS_STOCK with what it has, and nothing was saved`() {
            stock(301, 5)
            stock(202, 1)
            val before = listOf(line(1, 30, 301, 2))
            service.reserveForOrder(order, before, staff, now).getOrNull()!!.forEach(inventories::save)

            val refused = service.rebalanceForOrder(order, before, listOf(line(1, 20, 202, 2)), staff, now)

            assertEquals(Result.failure(InventoryDomainService.RebalanceFailure.OutOfStock(VariantId.constructFromPersisted(202), 1)), refused)
            assertEquals(mapOf("availableQuantity" to 1), refused.failureOrNull()?.meta)
            assertEquals(listOf(2, null), listOf(heldFor(301), heldFor(202)), "the old color is still held")
        }

        @Test
        fun `a color never stocked is out of stock with nothing left`() {
            stock(301, 5)
            val before = listOf(line(1, 30, 301, 1))
            service.reserveForOrder(order, before, staff, now).getOrNull()!!.forEach(inventories::save)

            val refused = service.rebalanceForOrder(order, before, listOf(line(1, 20, 209, 1)), staff, now)

            assertEquals(Result.failure(InventoryDomainService.RebalanceFailure.OutOfStock(VariantId.constructFromPersisted(209), 0)), refused)
        }
    }
}
