package com.kiva.domain.aggregate.inventory.model.valueobject

/**
 * Why staff changed the stock by hand (the contract's `reason`). Each reason allows only the direction it makes
 * sense in: a delivery can't take bags away and damage can't add any. Stored by name, so never renamed.
 */
enum class AdjustmentReason(val allowsIncrease: Boolean, val allowsDecrease: Boolean) {
    /** ورود کالا: a delivery from the workshop. */
    RESTOCK(allowsIncrease = true, allowsDecrease = false),

    /** شمارش انبار: the shelf count differs from the system, either way. */
    STOCKTAKE(allowsIncrease = true, allowsDecrease = true),

    /** آسیب‌دیده: bags that can't be sold any more. */
    DAMAGED(allowsIncrease = false, allowsDecrease = true),

    /** مرجوعی: a returned bag back on the shelf. */
    RETURNED(allowsIncrease = true, allowsDecrease = false),

    /** اصلاح دستی: fixing a mistake, either way. */
    MANUAL_CORRECTION(allowsIncrease = true, allowsDecrease = true),
    ;

    fun allows(delta: StockDelta): Boolean = if (delta.isIncrease) allowsIncrease else allowsDecrease
}
