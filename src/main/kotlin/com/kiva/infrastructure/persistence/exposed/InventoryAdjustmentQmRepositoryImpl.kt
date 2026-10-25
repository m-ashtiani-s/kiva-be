package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.InventoryAdjustmentQm
import com.kiva.application.repository.InventoryAdjustmentQmRepository
import com.kiva.common.event.valueobject.EventId
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.exposed.tbl.InventoryAdjustmentQmTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Reads inventoryadjustmentqm_tbl. Runs inside the use case's transaction; it never opens one itself. */
class InventoryAdjustmentQmRepositoryImpl : InventoryAdjustmentQmRepository {

    override fun findByEventId(eventId: EventId): InventoryAdjustmentQm? =
        InventoryAdjustmentQmTbl.selectAll().where { InventoryAdjustmentQmTbl.eventId eq UUID.fromString(eventId.value) }.singleOrNull()?.toQm()

    // Always newest first (the contract has no other order): by moment, then the later row, so two changes in one moment
    // keep the order they were made in and a row is never on two pages.
    override fun find(variantId: VariantId?, pageable: Pageable): Page<InventoryAdjustmentQm> {
        val query = InventoryAdjustmentQmTbl.selectAll()
        if (variantId != null) query.where { InventoryAdjustmentQmTbl.variantId eq variantId.value }
        val total = query.copy().count()
        val items = query.copy()
            .orderBy(InventoryAdjustmentQmTbl.occurredOn to SortOrder.DESC, InventoryAdjustmentQmTbl.id to SortOrder.DESC)
            .limit(pageable.size)
            .offset(pageable.offset)
            .map { it.toQm() }
        return Page(items, pageable.page, pageable.size, total)
    }

    private fun ResultRow.toQm() = InventoryAdjustmentQm(
        id = this[InventoryAdjustmentQmTbl.id].value,
        variantId = VariantId.constructFromPersisted(this[InventoryAdjustmentQmTbl.variantId]),
        sku = Sku.constructFromPersisted(this[InventoryAdjustmentQmTbl.sku]),
        delta = this[InventoryAdjustmentQmTbl.delta],
        reason = AdjustmentReason.valueOf(this[InventoryAdjustmentQmTbl.reason]),
        note = this[InventoryAdjustmentQmTbl.note],
        stockAfter = this[InventoryAdjustmentQmTbl.stockAfter],
        occurredOn = this[InventoryAdjustmentQmTbl.occurredOn].toInstant(),
        occurredBy = UserId.constructFromPersisted(this[InventoryAdjustmentQmTbl.occurredByUserId]),
    )
}
