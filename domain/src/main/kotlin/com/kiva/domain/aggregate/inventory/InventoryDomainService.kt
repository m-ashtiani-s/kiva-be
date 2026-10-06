package com.kiva.domain.aggregate.inventory

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentNote
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * Stock operations for the use cases. What it adds to [Inventory] is the one thing the aggregate can't know by
 * itself: a color nothing was recorded for yet simply has nothing in stock, so its stock starts empty instead of
 * being "not found".
 *
 * That the color exists at all is checked by the use case against the read model (step 5): from here only a
 * product's own stream could tell, and finding the product of a color would mean reading every product.
 *
 * It never saves and never opens a transaction: the use case loads, calls this, saves, all in its one transaction.
 * The reservation operations join here with the order flow; until then nothing calls them.
 */
class InventoryDomainService(
    private val inventoryRepository: InventoryRepository,
) {
    private val logger = getLogger()

    /** The stock of a color; empty when nothing was ever recorded for it. Never fails. */
    fun getInventory(variantId: VariantId): Result<Inventory, Nothing> {
        logger.info("getInventory variantId=${variantId.value}")
        return Result.success(inventoryRepository.get(variantId) ?: Inventory.empty(variantId))
    }

    /** A change staff made by hand to a color's stock. */
    fun adjustStock(
        variantId: VariantId,
        delta: StockDelta,
        reason: AdjustmentReason,
        note: AdjustmentNote?,
        by: UserId,
        now: Instant,
    ): Result<Inventory, AdjustStockFailure> {
        logger.info("adjustStock variantId=${variantId.value} delta=${delta.value} reason=$reason")

        val inventory = inventoryRepository.get(variantId) ?: Inventory.empty(variantId)
        return inventory.adjust(delta, reason, note, by, now).mapOnFailure {
            when (it) {
                is Inventory.AdjustFailure.WrongDirection -> AdjustStockFailure.WrongDirection(it.reason)
                is Inventory.AdjustFailure.MoreThanAvailable -> AdjustStockFailure.MoreThanAvailable(it.availableQuantity)
            }
        }
    }

    // ============ failures ============

    sealed class AdjustStockFailure(code: String, kind: FailureKind, meta: Map<String, Any>) :
        ResultFailure(code, kind, field = "delta", meta = meta) {
        data class WrongDirection(val reason: AdjustmentReason) :
            AdjustStockFailure("ADJUSTMENT_DIRECTION_INVALID", FailureKind.INVALID, mapOf("reason" to reason.name))

        data class MoreThanAvailable(val availableQuantity: Int) :
            AdjustStockFailure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, mapOf("availableQuantity" to availableQuantity))
    }
}
