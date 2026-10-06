package com.kiva.domain.aggregate.inventory

import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.inventory.InventoryTestData.delta
import com.kiva.domain.aggregate.inventory.InventoryTestData.lilac
import com.kiva.domain.aggregate.inventory.InventoryTestData.now
import com.kiva.domain.aggregate.inventory.InventoryTestData.staff
import com.kiva.domain.aggregate.inventory.InventoryTestData.stocked
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.DAMAGED
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.RESTOCK
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An in-memory stand-in for the port: what was saved, as loaded objects. */
private class InMemoryInventories : InventoryRepository {
    val saved = mutableMapOf<VariantId, Inventory>()
    override fun get(variantId: VariantId): Inventory? = saved[variantId]?.let { Inventory(it.getRecordedEvents()) }
    override fun save(inventory: Inventory) {
        saved[inventory.variantId] = inventory
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
}
