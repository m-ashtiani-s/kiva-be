package com.kiva.domain.aggregate.product.model.valueobject

/**
 * The badges a product card or color can wear, in the contract's order. Only the codes: the Persian text
 * («۱۷٪ تخفیف»), color and icon are the HTTP layer's job.
 *
 * The contract also has BESTSELLER, HANDMADE and CAMPAIGN; they come when there is data to decide them.
 */
enum class BadgeCode {
    /** «جدید»: until the product's newUntil. */
    NEW,

    /** «۱۷٪ تخفیف»: the discount shows as at least 1%. */
    SALE,

    /** «ویدیو دارد» */
    HAS_VIDEO,

    /** «ارسال رایگان»: the price alone reaches the free-shipping threshold. */
    FREE_SHIPPING,

    /** «تمام شد» */
    SOLD_OUT,

    /** «فقط ۳ عدد» */
    LOW_STOCK,
}
