package com.kiva.domain.aggregate.product.model.valueobject

/**
 * Where a product is in its life. Values from the API contract.
 * Which moves between them are allowed is a rule of the product itself, not of this list.
 */
enum class ProductStatus {
    /** Being prepared; shoppers can't see it. Every product starts here. */
    DRAFT,

    /** Published: in the shop, can be bought. */
    ACTIVE,

    /** Taken out of the shop for good. Old orders still point to it, so it is never deleted. */
    ARCHIVED,
}
