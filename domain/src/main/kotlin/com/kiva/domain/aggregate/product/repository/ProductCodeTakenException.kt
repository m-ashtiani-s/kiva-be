package com.kiva.domain.aggregate.product.repository

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.product.model.valueobject.Sku

/**
 * Saving a product was refused because another product took one of its codes (its slug or a SKU) after the caller
 * checked that they were free.
 *
 * The use cases check the codes first, so only two requests at the same moment get here: both pass the check, the
 * first saves, and the database refuses the second. It is a race, not a bug, so it isn't left to end in a 500; but it
 * is not a Result either, because the repository can't go on after the database refused a write. The transaction rolls
 * back; the use case answers what its own check would have answered.
 */
sealed class ProductCodeTakenException(message: String, cause: Throwable) : RuntimeException(message, cause) {

    class SlugTaken(val slug: Slug, cause: Throwable) :
        ProductCodeTakenException("Slug ${slug.value} was taken by another product", cause)

    /** @property sku the SKU, when the database said which one (it always does in practice). */
    class SkuTaken(val sku: Sku?, cause: Throwable) :
        ProductCodeTakenException("SKU ${sku?.value} was taken by another product", cause)
}
