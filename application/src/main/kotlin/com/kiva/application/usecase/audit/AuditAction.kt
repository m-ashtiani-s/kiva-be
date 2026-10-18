package com.kiva.application.usecase.audit

/**
 * What a panel write did, as the change log stores it (roadmap section 5.1). The contract leaves the list open; ours is
 * closed, so the HTTP layer has a Persian sentence for every value and its `when` stays complete. Each phase adds the
 * actions of its own endpoints; a stored name is never renamed.
 */
enum class AuditAction {
    USER_ROLES_CHANGED,

    CATEGORY_CREATED,
    CATEGORY_UPDATED,
    CATEGORY_DELETED,
    COLOR_CREATED,
    COLOR_UPDATED,
    COLOR_DELETED,
    MATERIAL_CREATED,
    MATERIAL_UPDATED,
    MATERIAL_DELETED,

    PRODUCT_CREATED,

    /** A `PUT` of the product that changed its price and nothing else. */
    PRODUCT_PRICE_CHANGED,
    PRODUCT_UPDATED,
    PRODUCT_PUBLISHED,
    PRODUCT_UNPUBLISHED,
    PRODUCT_ARCHIVED,
    PRODUCT_PUBLISH_SCHEDULED,
    PRODUCT_PUBLISH_UNSCHEDULED,

    VARIANT_ADDED,
    VARIANT_UPDATED,
    VARIANT_DEACTIVATED,

    INVENTORY_ADJUSTED,

    // ---- orders (phase 2) ----
    ORDER_NOTE_ADDED,
    ORDER_MEDIA_SET,
    ORDER_PHOTO_SENT,
}

/** The kind of thing a panel write changed: the contract's AuditEntityType, value for value. */
enum class AuditEntityType {
    PRODUCT,
    VARIANT,
    INVENTORY,

    /** In the contract, never written: a media upload is not logged. */
    MEDIA,
    CATEGORY,
    COLOR,
    MATERIAL,
    ORDER,
    RETURN,
    REFUND,
    DISCOUNT_CODE,
    CAMPAIGN,
    BANNER,
    ANNOUNCEMENT,
    TESTIMONIAL,
    FAQ,
    PAGE,
    SETTINGS,
    REVIEW,
    CONTACT_MESSAGE,
    USER,
}
