package com.kiva.domain.aggregate.product.model.valueobject

/** What a bag suits («مناسب برای»), for the spec table and the occasion filter. Values from the API contract. */
enum class Occasion {
    /** روزمره */
    DAILY,

    /** دانشگاه */
    UNIVERSITY,

    /** محل کار */
    WORK,

    /** رسمی */
    FORMAL,

    /** مهمانی و مجالس */
    PARTY,

    /** سفر */
    TRAVEL,

    /** خرید */
    SHOPPING,

    /** دورهمی */
    GATHERING,
}
