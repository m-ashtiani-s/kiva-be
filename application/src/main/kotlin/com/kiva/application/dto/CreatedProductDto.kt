package com.kiva.application.dto

/**
 * What CreateProductUc made: only the new ids, as a command returns. The product itself is read with a query.
 *
 * @property variantIds the id of each new color, by its key (`lilac` to 101).
 */
data class CreatedProductDto(
    val productId: Long,
    val variantIds: Map<String, Long>,
)
