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
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
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

    /**
     * Holds the stock of every line of a new order, all or nothing (roadmap section 11): when any color has too few
     * left, nothing is held, and the failure names every such color.
     *
     * The stocks come back **in lock order**, ascending (product, color), and must be saved in that order: every writer
     * takes the read model's rows in that one order, so two orders of the same colors listed the other way round never
     * wait on each other in a circle.
     */
    fun reserveForOrder(orderCode: OrderCode, lines: List<OrderLine>, by: UserId, now: Instant): Result<List<Inventory>, ReserveForOrderFailure> {
        logger.info("reserveForOrder orderCode=${orderCode.value} lines=${lines.map { "${it.variantId.value}x${it.quantity.value}" }}")

        val short = mutableListOf<VariantId>()
        val reserved = lines.sortedWith(compareBy({ it.productId.value }, { it.variantId.value })).mapNotNull { line ->
            val inventory = inventoryRepository.get(line.variantId) ?: Inventory.empty(line.variantId)
            when (val failure = inventory.reserve(orderCode, line.quantity, by, now).failureOrNull()) {
                null -> inventory
                is Inventory.ReserveFailure.MoreThanAvailable -> null.also { short += line.variantId }
                // A new order's code has reserved nothing yet, anywhere: anything else is a bug.
                else -> error("Order ${orderCode.value} could not reserve variant ${line.variantId.value}: ${failure.code}")
            }
        }
        return if (short.isEmpty()) Result.success(reserved) else Result.failure(ReserveForOrderFailure.OutOfStock(short))
    }

    // ============ failures ============

    sealed class AdjustStockFailure(code: String, kind: FailureKind, meta: Map<String, Any>) :
        ResultFailure(code, kind, field = "delta", meta = meta) {
        data class WrongDirection(val reason: AdjustmentReason) :
            AdjustStockFailure("ADJUSTMENT_DIRECTION_INVALID", FailureKind.INVALID, mapOf("reason" to reason.name))

        data class MoreThanAvailable(val availableQuantity: Int) :
            AdjustStockFailure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, mapOf("availableQuantity" to availableQuantity))
    }

    sealed class ReserveForOrderFailure(code: String, kind: FailureKind, meta: Map<String, Any>) : ResultFailure(code, kind, meta = meta) {
        /** These colors have fewer left than the order asks for; the use case names the lines they are on. */
        data class OutOfStock(val variantIds: List<VariantId>) :
            ReserveForOrderFailure("OUT_OF_STOCK", FailureKind.BUSINESS_RULE, mapOf("variantIds" to variantIds.map { it.value }))
    }
}
