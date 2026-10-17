package com.kiva.application.repository

import com.kiva.application.dto.UploadedMediaDto
import com.kiva.domain.aggregate.cart.model.PurchaseOffer

/**
 * One color as the cart shows it, read from the read model: what the domain needs to decide ([offer]), and what the
 * shopper sees of it. The exact stock is in [offer]; only the cart's rules and StockPolicy turn it into what a shopper
 * may see.
 *
 * @property image the color's first photo (videos left out), or null while it has none: the cart line's picture, and
 *   the picture an order keeps of it.
 */
data class OfferQm(
    val offer: PurchaseOffer,
    val product: Product,
    val category: Category,
    val color: Color,
    val image: UploadedMediaDto? = null,
) {
    data class Product(val slug: String, val sku: String, val name: String)

    data class Category(val id: Long, val slug: String, val name: String)

    /** The palette's color, and [sku] the code of this very color of the bag (`KV-1004-CARAMEL`), which an order keeps. */
    data class Color(val key: String, val name: String, val hex: String, val isLight: Boolean, val sortOrder: Int, val sku: String)
}
