package com.kiva.domain.aggregate.order.model.valueobject

/** Why an order's personal data was erased. Stored with the event by name, so a value in use is never renamed. */
enum class ErasureReason {
    /** Its customer deleted their account (`DELETE /me`). */
    ACCOUNT_DELETED,
}
