package com.kiva.domain.aggregate.product.model.valueobject

/** From which side a photo shows the bag. Values from the API contract; the Persian label is the HTTP layer's job. */
enum class MediaView {
    /** نمای جلو */
    FRONT,

    /** نمای کنار */
    SIDE,

    /** پشت */
    BACK,

    /** جزئیات */
    DETAIL,

    /** داخل کیف */
    INTERIOR,

    /** استایل / لایف‌استایل */
    STYLE,

    /** روی مدل */
    ON_MODEL,

    /** مقایسه‌ی اندازه */
    SCALE,
}
