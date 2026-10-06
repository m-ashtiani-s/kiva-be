package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentNote
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.DAMAGED
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.RESTOCK
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.STOCKTAKE
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stock stored as events in kiva_test and read back, each step in its own transaction as in the app.
 * Every color is one of a real stored product, because saving stock projects it into that color's read model row.
 * Everything is deleted after each test.
 */
class InventoryRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = InventoryRepositoryImpl()
    private val catalog = StoredCatalog()

    private val staff = UserId.constructFromPersisted(12)
    private val now = Instant.parse("2026-10-06T08:00:00Z")

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    private fun newColor(): VariantId = catalog.newColor()

    private fun delta(value: Int) = StockDelta.constructFromPersisted(value)
    private fun order(code: String) = OrderCode.constructFromPersisted(code)

    private fun Result<Inventory, *>.done() = assertTrue(isSuccess, "refused: $this")

    private fun Inventory.snapshot(): List<Any?> = listOf(variantId, onHand, reserved, available, reservations)

    private fun read(variantId: VariantId): Inventory? = transaction(db) { repository.get(variantId) }

    private fun save(inventory: Inventory) = transaction(db) { repository.save(inventory) }

    private fun streamLength(variantId: VariantId): Long = transaction(db) {
        EventStoreTbl.selectAll()
            .where { (EventStoreTbl.aggregateName eq "INVENTORY") and (EventStoreTbl.aggregateId eq variantId.value.toString()) }
            .count()
    }

    /** 12 delivered, one order reserved 2 and shipped, another reserved 3, one damaged: 5 events. Saved. */
    private fun storedStock(): Inventory {
        val inventory = Inventory.empty(newColor())
        inventory.adjust(delta(12), RESTOCK, AdjustmentNote.constructFromPersisted("بارنامه ۲۳۴"), staff, now).done()
        inventory.reserve(order("KV-218340"), Quantity.constructFromPersisted(2), UserId.SYSTEM, now).done()
        inventory.ship(order("KV-218340"), staff, now).done()
        inventory.reserve(order("KV-218341"), Quantity.constructFromPersisted(3), UserId.SYSTEM, now).done()
        inventory.adjust(delta(-1), DAMAGED, null, staff, now).done()
        save(inventory)
        return inventory
    }

    @Test
    fun `saved stock reads back the same, reservations and shipped orders included`() {
        val saved = storedStock()

        val loaded = read(saved.variantId)!!

        assertEquals(saved.snapshot(), loaded.snapshot())
        assertEquals(listOf(9, 3, 6), listOf(loaded.onHand, loaded.reserved, loaded.available))
        assertEquals(5, streamLength(saved.variantId))
        // It remembers the shipped order too.
        assertTrue(loaded.reserve(order("KV-218340"), Quantity.constructFromPersisted(2), UserId.SYSTEM, now).isFailure)
    }

    @Test
    fun `a color nothing was recorded for is null, so the domain service starts it empty`() {
        assertNull(read(newColor()))
    }

    @Test
    fun `saving again adds only the new events`() {
        val loaded = read(storedStock().variantId)!!
        loaded.adjust(delta(4), STOCKTAKE, null, staff, now).done()

        save(loaded)

        assertEquals(6, streamLength(loaded.variantId))
        assertEquals(loaded.snapshot(), read(loaded.variantId)?.snapshot())
    }

    @Test
    fun `a product stream with the same number is not read as the stock`() {
        val saved = storedStock()
        transaction(db) {
            EventStoreTbl.insert {
                it[eventId] = UUID.randomUUID()
                it[aggregateName] = "PRODUCT"
                it[aggregateId] = saved.variantId.value.toString()
                // Not 1: the color's own product may happen to have the same number, and its stream starts at 1.
                it[sequence] = 1_000
                it[eventType] = "ProductCreated"
                it[payloadVersion] = 1
                it[payload] = "{}"
                it[occurredOn] = now.atOffset(ZoneOffset.UTC)
                it[occurredByUserId] = 0
            }
        }

        assertEquals(saved.snapshot(), read(saved.variantId)?.snapshot())
    }

    @Test
    fun `two purchases of the last bags at once - the second is refused and nothing of it is stored`() {
        val color = newColor()
        save(Inventory.empty(color).also { it.adjust(delta(2), RESTOCK, null, staff, now).done() })
        val first = read(color)!!
        val second = read(color)!!

        // Both see 2 available, so for each on its own the reservation is fine.
        first.reserve(order("KV-300001"), Quantity.constructFromPersisted(2), UserId.SYSTEM, now).done()
        second.reserve(order("KV-300002"), Quantity.constructFromPersisted(2), UserId.SYSTEM, now).done()
        save(first)

        val refused = assertFailsWith<ConcurrentChangeException> { save(second) }

        assertEquals("INVENTORY", refused.aggregateName.value)
        assertEquals(color.value.toString(), refused.aggregateId.value)
        assertEquals(mapOf(order("KV-300001") to Quantity.constructFromPersisted(2)), read(color)?.reservations)
    }

    @Test
    fun `even the very first change of a color can't be saved twice at once`() {
        val color = newColor()
        val first = Inventory.empty(color).also { it.adjust(delta(5), RESTOCK, null, staff, now).done() }
        val second = Inventory.empty(color).also { it.adjust(delta(7), RESTOCK, null, staff, now).done() }
        save(first)

        assertFailsWith<ConcurrentChangeException> { save(second) }
        assertEquals(5, read(color)?.onHand)
    }
}
