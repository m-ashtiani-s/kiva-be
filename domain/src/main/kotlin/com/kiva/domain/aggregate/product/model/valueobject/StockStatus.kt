package com.kiva.domain.aggregate.product.model.valueobject

/** How much of a color is left, as shoppers see it. Values from the API contract. */
enum class StockStatus {
    /** Plenty; the exact number is not shown. */
    IN_STOCK,

    /** A few left («فقط ۳ عدد»); only now is the number shown. */
    LOW_STOCK,

    /** None left; can't be bought. */
    OUT_OF_STOCK,
}
