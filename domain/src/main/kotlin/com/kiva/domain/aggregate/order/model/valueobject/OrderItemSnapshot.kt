package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku

/**
 * What a bag was called and looked like when it was bought, copied from the catalog when the order is placed: a
 * renamed product or a recolored palette never changes an order (the contract's `OrderItem.name`, «snapshot نام در
 * زمان خرید»).
 *
 * @property sku the color's SKU (`KV-1004-CARAMEL`), which the warehouse picks by.
 * @property imageUrl the color's first photo at the size a line shows it (480), as a path under the media root
 *   (`2026/10/med_…/480.webp`): the HTTP layer puts the public address in front, so moving the files is a change of
 *   configuration. Null when the color had no photo then, as for every order placed before photos (step 1.9).
 */
data class OrderItemSnapshot(
    val name: ProductName,
    val slug: Slug,
    val sku: Sku,
    val color: Color,
    val imageUrl: String?,
) {
    /** The color as the palette named and painted it then. */
    data class Color(val key: ColorKey, val name: String, val hex: String) {
        init {
            require(name.isNotBlank()) { "A color needs its name: ${key.value}" }
            require(HEX.matches(hex)) { "Invalid color hex '$hex' of ${key.value}" }
        }

        private companion object {
            val HEX = Regex("#[0-9A-Fa-f]{6}")
        }
    }
}
