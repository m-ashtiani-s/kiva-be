package com.kiva.domain.aggregate.product.model.valueobject

/**
 * What a shopper may know about the stock of one color (the contract's StockInfo, without the Persian label,
 * which is the HTTP layer's job). Made by StockPolicy; the exact stock never leaves the shop.
 *
 * @property shownQuantity the number left, only when [status] is LOW_STOCK; null otherwise.
 * @property maxOrderQuantity the most a cart may hold: what is left, but no more than the product's limit per order.
 */
data class StockInfo(
    val status: StockStatus,
    val shownQuantity: Int?,
    val maxOrderQuantity: Int,
)
