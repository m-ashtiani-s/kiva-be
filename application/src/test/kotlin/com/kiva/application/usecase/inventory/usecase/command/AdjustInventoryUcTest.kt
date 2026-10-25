package com.kiva.application.usecase.inventory.usecase.command

import com.kiva.application.dto.ActorNameDto
import com.kiva.application.dto.InventoryAdjustmentDto
import com.kiva.application.port.AuditChange
import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.InventoryAdjustmentQm
import com.kiva.application.repository.InventoryAdjustmentQmRepository
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductFacetsQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductRef
import com.kiva.application.repository.VariantQm
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.UnusedProductQmRepository
import com.kiva.application.util.UnusedUserRepository
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventId
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.entity.event.InventoryDomainEvent
import com.kiva.domain.aggregate.inventory.entity.event.StockAdjusted
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.repository.UserRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Stock kept in memory as events, like the real repository. It can refuse the next saves as if another request had
 * saved first, running [someoneElse] at that moment to make that other request's change.
 */
private class InMemoryInventories : InventoryRepository {
    val streams = mutableMapOf<VariantId, List<InventoryDomainEvent>>()
    var refuseNextSaves = 0
    var someoneElse: () -> Unit = {}

    override fun get(variantId: VariantId): Inventory? = streams[variantId]?.let { Inventory(it) }

    override fun save(inventory: Inventory) {
        if (refuseNextSaves > 0) {
            refuseNextSaves--
            someoneElse()
            throw ConcurrentChangeException(InventoryDomainEvent.AGGREGATE_NAME, EventAggregateId.of(inventory.variantId.value))
        }
        streams[inventory.variantId] = streams[inventory.variantId].orEmpty() + inventory.getRecordedEvents()
    }

    /** A change saved by another request, outside the use case under test. */
    fun changedElsewhere(variantId: VariantId, change: (Inventory) -> Unit) {
        val inventory = get(variantId) ?: Inventory.empty(variantId)
        change(inventory)
        streams[variantId] = streams[variantId].orEmpty() + inventory.getRecordedEvents()
    }
}

/** The read model as far as stock needs it: which colors some product has. */
private class KnownColors(vararg colors: VariantQm) : UnusedProductQmRepository() {
    private val byId = colors.associateBy { it.variantId }

    override fun findVariant(variantId: VariantId): VariantQm? = byId[variantId]

    override fun findDetail(ref: ProductRef): ProductDetailQm? = error("not used here")

    override fun findRelated(productId: ProductId, limit: Int): List<ProductCardQm>? = error("not used here")

    override fun findTakenSkus(skus: Collection<Sku>): Set<Sku> = error("not used here")

    override fun countActiveByMaterial(): Map<Slug, Int> = error("not used here")

    override fun refreshSearchText(of: ProductQmRepository.NamedInSearch): Int = error("not used here")
}

/** The history as the projector writes it: one row per StockAdjusted, with the stock on hand after it. */
private class History(private val inventories: InMemoryInventories) : InventoryAdjustmentQmRepository {
    override fun findByEventId(eventId: EventId): InventoryAdjustmentQm? {
        val (variantId, stream) = inventories.streams.entries.firstOrNull { (_, events) -> events.any { it.id == eventId } } ?: return null
        val upTo = stream.takeWhile { it.id != eventId } + stream.first { it.id == eventId }
        val event = upTo.last() as StockAdjusted
        return InventoryAdjustmentQm(
            id = 7, variantId = variantId, sku = Sku.constructFromPersisted("KV-1001-LILAC"), delta = event.delta.value, reason = event.reason,
            note = event.note?.value, stockAfter = Inventory(upTo).onHand, occurredOn = event.occurredOn, occurredBy = event.occurredByUserId,
        )
    }

    override fun find(variantId: VariantId?, pageable: Pageable): Page<InventoryAdjustmentQm> = error("not used here")
}

class AdjustInventoryUcTest {

    private val now = Instant.parse("2026-10-06T08:00:00Z")
    private val staff = UserId.constructFromPersisted(1)
    private val actor = PanelActor(staff, "10.0.0.1")
    private val audit = mutableListOf<AuditEntry>()
    private val lilac = VariantId.constructFromPersisted(101)

    private val transactionManager = MockTransactionManager()
    private val inventories = InMemoryInventories()
    private val useCase = AdjustInventoryUc(
        transactionManager,
        InventoryDomainService(inventories),
        inventories,
        KnownColors(
            VariantQm(lilac, ProductId.constructFromPersisted(1), ColorKey.constructFromPersisted("lilac"), Sku.constructFromPersisted("KV-1001-LILAC"), isActive = true),
        ),
        History(inventories),
        object : UnusedUserRepository() {
            override fun findById(id: UserId): User? = null
        },
        object : AuditTrail {
            override fun record(entry: AuditEntry) {
                audit += entry
            }

            override fun deleteRecordedBefore(before: Instant, limit: Int): Int = 0
        },
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun cmd(delta: Int, reason: String, note: String? = null, variantId: Long = 101) =
        AdjustInventoryUc.Cmd(variantId, delta, reason, note, actor)

    /** [onHand] bags of lilac delivered before the test, [reserved] of them promised to one order. */
    private fun lilacInStock(onHand: Int, reserved: Int = 0) = inventories.changedElsewhere(lilac) {
        it.adjust(StockDelta.constructFromPersisted(onHand), AdjustmentReason.RESTOCK, null, staff, now)
        if (reserved > 0) it.reserve(OrderCode.constructFromPersisted("KV-218340"), Quantity.constructFromPersisted(reserved), UserId.SYSTEM, now)
    }

    private fun stored(): Inventory? = inventories.get(lilac)

    @Nested
    inner class Adjusting {
        @Test
        fun `a delivery to a color nothing was recorded for starts its stock, in one transaction`() = runTest {
            val result = useCase.execute(cmd(12, "RESTOCK", note = "  بارنامه ۲۳۴ "))

            assertEquals(
                Result.success(InventoryAdjustmentDto(7, 101, "KV-1001-LILAC", 12, AdjustmentReason.RESTOCK, "بارنامه ۲۳۴", 12, now, ActorNameDto.Unknown)),
                result,
            )
            val row = audit.single()
            assertEquals(listOf<Any?>(AuditAction.INVENTORY_ADJUSTED, AuditEntityType.INVENTORY, "101", "KV-1001-LILAC"), listOf(row.action, row.entityType, row.entityId, row.entityLabel))
            assertEquals(listOf(AuditChange("stockOnHand", "0", "12"), AuditChange("reason", null, "RESTOCK")), row.changes)
            assertEquals(12, stored()?.onHand)
            assertEquals(1, transactionManager.transactionsOpened)
        }

        @Test
        fun `the stock after is what is on hand, reserved bags included`() = runTest {
            lilacInStock(onHand = 10, reserved = 3)

            val result = useCase.execute(cmd(-2, "DAMAGED"))

            assertEquals(8, result.getOrNull()?.stockAfter)
            assertEquals(listOf(AuditChange("stockOnHand", "10", "8"), AuditChange("reason", null, "DAMAGED")), audit.single().changes)
        }
    }

    @Nested
    inner class Refusing {
        @Test
        fun `every wrong field is reported at once, before any transaction`() = runTest {
            val result = useCase.execute(cmd(0, "STOLEN", note = "ب".repeat(301), variantId = 0))

            val failure = result.failureOrNull()!!
            assertEquals("VALIDATION_ERROR", failure.code)
            assertEquals(listOf("variantId", "delta", "reason", "note"), failure.causes.map { it.field })
            assertEquals(
                listOf("VARIANT_ID_INVALID", "STOCK_DELTA_INVALID", "ADJUSTMENT_REASON_INVALID", "ADJUSTMENT_NOTE_TOO_LONG"),
                failure.causes.map { it.code },
            )
            assertEquals(0, transactionManager.transactionsOpened)
        }

        @Test
        fun `a color no product has is VARIANT_NOT_FOUND on variantId, and nothing is stored`() = runTest {
            val nobodys = VariantId.constructFromPersisted(999)

            val result = useCase.execute(cmd(5, "RESTOCK", variantId = 999))

            assertEquals(Result.failure(AdjustInventoryUc.Failure.VariantNotFound(nobodys)), result)
            assertEquals(listOf("variantId", FailureKind.INVALID), result.failureOrNull()?.let { listOf(it.field, it.kind) })
            assertNull(inventories.get(nobodys))
        }

        @Test
        fun `a reason that goes the other way is refused on delta and nothing is stored`() = runTest {
            val result = useCase.execute(cmd(3, "DAMAGED"))

            assertEquals(Result.failure(AdjustInventoryUc.Failure.WrongDirection(AdjustmentReason.DAMAGED)), result)
            assertEquals("delta", result.failureOrNull()?.field)
            assertNull(stored())
        }

        @Test
        fun `taking away more than can be sold is QUANTITY_EXCEEDS_STOCK with how many can`() = runTest {
            lilacInStock(onHand = 5, reserved = 3)

            val result = useCase.execute(cmd(-3, "DAMAGED"))

            assertEquals(Result.failure(AdjustInventoryUc.Failure.MoreThanAvailable(2)), result)
            assertEquals(FailureKind.BUSINESS_RULE, result.failureOrNull()?.kind)
            assertEquals(5, stored()?.onHand)
        }
    }

    /** Another request saved a change to the same stock between this one's read and its save. */
    @Nested
    inner class SomeoneElseSavedFirst {
        @Test
        fun `it reads the stock again and the change lands on top of the other one`() = runTest {
            lilacInStock(onHand = 5)
            inventories.refuseNextSaves = 1
            inventories.someoneElse = {
                inventories.changedElsewhere(lilac) { it.adjust(StockDelta.constructFromPersisted(2), AdjustmentReason.RESTOCK, null, staff, now) }
            }

            val result = useCase.execute(cmd(-1, "DAMAGED"))

            assertEquals(6, result.getOrNull()?.stockAfter)
            assertEquals(6, stored()?.onHand)
            assertEquals(2, transactionManager.transactionsOpened)
        }

        @Test
        fun `the rules are checked again on the fresh stock, so bags just reserved are not taken away`() = runTest {
            lilacInStock(onHand = 5)
            inventories.refuseNextSaves = 1
            inventories.someoneElse = {
                inventories.changedElsewhere(lilac) {
                    it.reserve(OrderCode.constructFromPersisted("KV-218340"), Quantity.constructFromPersisted(3), UserId.SYSTEM, now)
                }
            }

            val result = useCase.execute(cmd(-4, "DAMAGED"))

            assertEquals(Result.failure(AdjustInventoryUc.Failure.MoreThanAvailable(2)), result)
            assertEquals(listOf(5, 3), stored()?.let { listOf(it.onHand, it.reserved) })
        }

        @Test
        fun `after three refusals in a row it gives up and the conflict goes up to the caller`() = runTest {
            lilacInStock(onHand = 5)
            inventories.refuseNextSaves = 3

            assertFailsWith<ConcurrentChangeException> { useCase.execute(cmd(-1, "DAMAGED")) }
            assertEquals(AdjustInventoryUc.MAX_ATTEMPTS, transactionManager.transactionsOpened)
            assertEquals(5, stored()?.onHand)
        }
    }
}
