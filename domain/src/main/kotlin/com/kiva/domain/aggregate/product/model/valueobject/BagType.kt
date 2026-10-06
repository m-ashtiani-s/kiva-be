package com.kiva.domain.aggregate.product.model.valueobject

/**
 * The form of a bag. It picks the illustration (category icons, product placeholders) and the default spec table.
 * Values and names from the API contract.
 */
enum class BagType {
    /** دوشی هوبو */
    HOBO,

    /** کراس‌بادی */
    CROSSBODY,

    /** کوله */
    BACKPACK,

    /** دستی */
    SATCHEL,

    /** کلاچ / مجلسی */
    CLUTCH,

    /** شاپر / توت */
    TOTE,

    /** سطلی */
    BUCKET,

    /** کیف پول */
    WALLET,
}
