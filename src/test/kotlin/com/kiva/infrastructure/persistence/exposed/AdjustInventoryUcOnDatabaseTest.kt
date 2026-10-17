package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.test.assertEquals

/**
 * AdjustInventoryUc with everything real: Exposed transactions, the event store on kiva_test and its unique key.
 * It proves what the use case's own test can only pretend: after a real refused insert the transaction is rolled back,
 * and the retry runs in a fresh one that sees the other request's change.
 */
class AdjustInventoryUcOnDatabaseTest {

    private val db = TestDatabase.database
    private val catalog = StoredCatalog()
    private val inventories = InventoryRepositoryImpl()
    private val staff = UserId.constructFromPersisted(12)

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    /**
     * The real repository, except that just before the first save another request saves its own change and commits.
     * It runs on its own thread, so it has its own connection and transaction, as a second HTTP request would.
     */
    private inner class AnotherRequestSavesFirst(private val otherChange: () -> Unit) : InventoryRepository {
        private var done = false

        override fun get(variantId: VariantId): Inventory? = inventories.get(variantId)

        override fun save(inventory: Inventory) {
            if (!done) {
                done = true
                thread { transaction(db) { otherChange() } }.join()
            }
            inventories.save(inventory)
        }
    }

    private fun delivery(variantId: VariantId, bags: Int) {
        val inventory = inventories.get(variantId) ?: Inventory.empty(variantId)
        inventory.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, staff, Instant.now())
        inventories.save(inventory)
    }

    @Test
    fun `a real refused save is rolled back and the retry lands on top of the other request's change`() = runTest {
        val lilac = catalog.newProduct(listOf(StoredCatalog.NewColor("lilac"))).variants.single()
        val color = lilac.id
        transaction(db) { delivery(color, 5) }

        val repository = AnotherRequestSavesFirst { delivery(color, 2) }
        val useCase = AdjustInventoryUc(
            ExposedTransactionManager(db), InventoryDomainService(repository), repository, ProductQmRepositoryImpl(), InventoryAdjustmentQmRepositoryImpl(),
            UserRepositoryImpl(), AuditTrailImpl(), Clock.systemUTC(),
        )

        val result = useCase.execute(AdjustInventoryUc.Cmd(color.value, -1, "DAMAGED", null, PanelActor(staff, null)))

        // 5 delivered, 2 more by the other request, 1 damaged by this one: nothing lost, nothing counted twice.
        assertEquals(6, result.getOrNull()?.stockAfter)
        assertEquals(lilac.sku.value, result.getOrNull()?.sku)
        assertEquals(6, transaction(db) { inventories.get(color)?.onHand })
        val stockEvents = transaction(db) {
            EventStoreTbl.selectAll()
                .where { (EventStoreTbl.aggregateName eq "INVENTORY") and (EventStoreTbl.aggregateId eq color.value.toString()) }
                .count()
        }
        assertEquals(3, stockEvents)
    }
}
