package com.kiva.domain.aggregate.discount.model.valueobject

/** The id of a discount code (its row's). Codes are made by SQL until the panel's discount codes are built. */
@JvmInline
value class DiscountCodeId private constructor(val value: Long) {

    init {
        require(value > 0) { "Invalid DiscountCodeId $value" }
    }

    companion object {
        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): DiscountCodeId = DiscountCodeId(value)
    }
}

/** How a code takes money off: a percent of what it covers, or a fixed amount in toman (contract `type`). */
enum class DiscountType {
    PERCENT,
    FIXED,
}

/**
 * Where an order's use of a code is: held while the order waits for its money (it counts against the code's limits all
 * the same), confirmed once paid, released when the order expires (and the code may be used again).
 */
enum class DiscountRedemptionStatus {
    HELD,
    CONFIRMED,
    RELEASED,
}
