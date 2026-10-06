package com.kiva.domain.aggregate.product.model.valueobject

/** How a bag closes. Values from the API contract; the Persian label is the HTTP layer's job. */
enum class ClosureType {
    /** زیپ فلزی */
    ZIPPER,

    /** زیپ دوطرفه */
    DOUBLE_ZIPPER,

    /** زیپ + قفل تزئینی */
    ZIPPER_WITH_DECORATIVE_LOCK,

    /** درب مگنتی */
    MAGNETIC_FLAP,

    /** دکمه مگنتی */
    MAGNETIC_SNAP,

    /** بند کشی */
    DRAWSTRING,

    /** دکمه فلزی */
    METAL_BUTTON,

    /** بدون درب */
    OPEN_TOP,
}
