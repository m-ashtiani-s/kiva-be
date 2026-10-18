package com.kiva.domain.aggregate.inventory

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.valueobject.Quantity
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

    /**
     * Gives back what an order held (it expired; later, it was cancelled), every line of it, **in lock order** like
     * [reserveForOrder]: the stocks come back sorted by (product, color) and must be saved in that order. A color that
     * holds nothing for the order any more (given back already, or shipped) changes nothing, so running twice is harmless.
     */
    fun releaseForOrder(orderCode: OrderCode, lines: List<OrderLine>, by: UserId, now: Instant): List<Inventory> {
        logger.info("releaseForOrder orderCode=${orderCode.value} variants=${lines.map { it.variantId.value }}")
        return lines.sortedWith(compareBy({ it.productId.value }, { it.variantId.value })).mapNotNull { line ->
            val inventory = inventoryRepository.get(line.variantId) ?: return@mapNotNull null
            inventory.releaseReservation(orderCode, by, now)
            inventory.takeIf { it.getRecordedEvents().isNotEmpty() }
        }
    }

    /**
     * What an order held leaves with the courier: every line's stock ships, **in lock order** like [reserveForOrder]
     * (sorted by product, then color; saved in that order). A color that shipped for the order already changes nothing,
     * so shipping twice is harmless. A paid order not shipped yet always holds its stock: a line holding nothing is a bug.
     */
    fun shipForOrder(orderCode: OrderCode, lines: List<OrderLine>, by: UserId, now: Instant): List<Inventory> = shipForOrders(mapOf(orderCode to lines), by, now)

    /**
     * Several orders leave in one parcel (a reservation group): every color of all of them, **in one lock order** across
     * the orders (by product, then color), each color read once and shipped for each order on it. Two groups sharing
     * colors in a different order of their own lines never wait on each other the wrong way round.
     */
    fun shipForOrders(orders: Map<OrderCode, List<OrderLine>>, by: UserId, now: Instant): List<Inventory> {
        logger.info("shipForOrders orderCodes=${orders.keys.map { it.value }}")
        val byVariant = orders.flatMap { (code, lines) -> lines.map { line -> line to code } }
            .groupBy({ it.first.productId.value to it.first.variantId }, { it.second })
        return byVariant.keys.sortedWith(compareBy({ it.first }, { it.second.value })).mapNotNull { key ->
            val variantId = key.second
            val inventory = inventoryRepository.get(variantId) ?: error("Variant ${variantId.value} ships for an order but has no stock")
            byVariant.getValue(key).distinct().forEach { code ->
                inventory.ship(code, by, now).failureOrNull()?.let { error("Order ${code.value} could not ship variant ${variantId.value}: ${it.code}") }
            }
            inventory.takeIf { it.getRecordedEvents().isNotEmpty() }
        }
    }

    /**
     * An order's lines changed by a change request ([before] to [after]: a color swapped, a line taken out): what the order
     * holds of each color follows, all or nothing. For each color whose total quantity in the order changed, what the
     * order held is given back and the new total is held again, so a color that is now on two lines is held once, with
     * both quantities (an Inventory holds one quantity per order). In lock order like [reserveForOrder]: the stocks come
     * back sorted by (product, color) and must be saved in that order.
     *
     * A color with too few left for its new total is refused with what it has left after the order's own hold went back
     * ([RebalanceFailure.OutOfStock]); nothing is to be saved then.
     */
    fun rebalanceForOrder(orderCode: OrderCode, before: List<OrderLine>, after: List<OrderLine>, by: UserId, now: Instant): Result<List<Inventory>, RebalanceFailure> {
        logger.info("rebalanceForOrder orderCode=${orderCode.value} before=${before.map { "${it.variantId.value}x${it.quantity.value}" }} after=${after.map { "${it.variantId.value}x${it.quantity.value}" }}")
        fun totals(lines: List<OrderLine>) = lines.groupBy({ it.productId.value to it.variantId }, { it.quantity.value }).mapValues { it.value.sum() }
        val held = totals(before)
        val wanted = totals(after)

        val changed = (held.keys + wanted.keys).filter { held[it] != wanted[it] }.sortedWith(compareBy({ it.first }, { it.second.value }))
        val touched = changed.map { key ->
            val variantId = key.second
            val inventory = inventoryRepository.get(variantId) ?: Inventory.empty(variantId)
            inventory.releaseReservation(orderCode, by, now)
            wanted[key]?.let { quantity ->
                when (val failure = inventory.reserve(orderCode, Quantity.constructFromPersisted(quantity), by, now).failureOrNull()) {
                    null -> {}
                    is Inventory.ReserveFailure.MoreThanAvailable -> return Result.failure(RebalanceFailure.OutOfStock(variantId, failure.availableQuantity))
                    // Its hold for the order went back just above, and an order changes only before it ships: anything else is a bug.
                    else -> error("Order ${orderCode.value} could not hold variant ${variantId.value} again: ${failure.code}")
                }
            }
            inventory
        }
        return Result.success(touched.filter { it.getRecordedEvents().isNotEmpty() })
    }

    // ============ failures ============

    sealed class AdjustStockFailure(code: String, kind: FailureKind, meta: Map<String, Any>) :
        ResultFailure(code, kind, field = "delta", meta = meta) {
        data class WrongDirection(val reason: AdjustmentReason) :
            AdjustStockFailure("ADJUSTMENT_DIRECTION_INVALID", FailureKind.INVALID, mapOf("reason" to reason.name))

        data class MoreThanAvailable(val availableQuantity: Int) :
            AdjustStockFailure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, mapOf("availableQuantity" to availableQuantity))
    }

    sealed class RebalanceFailure(code: String, kind: FailureKind, meta: Map<String, Any>) : ResultFailure(code, kind, meta = meta) {
        /** [variantId] has only [availableQuantity] left for the order's new total of it (422). */
        data class OutOfStock(val variantId: VariantId, val availableQuantity: Int) :
            RebalanceFailure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, mapOf("availableQuantity" to availableQuantity))
    }

    sealed class ReserveForOrderFailure(code: String, kind: FailureKind, meta: Map<String, Any>) : ResultFailure(code, kind, meta = meta) {
        /** These colors have fewer left than the order asks for; the use case names the lines they are on. */
        data class OutOfStock(val variantIds: List<VariantId>) :
            ReserveForOrderFailure("OUT_OF_STOCK", FailureKind.BUSINESS_RULE, mapOf("variantIds" to variantIds.map { it.value }))
    }
}
