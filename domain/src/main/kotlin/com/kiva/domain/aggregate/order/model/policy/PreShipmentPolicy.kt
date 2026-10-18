package com.kiva.domain.aggregate.order.model.policy

/**
 * The rules of the photos before shipping (roadmap section 7.2, rows 6, 7 and 13).
 *
 * [FEEDBACK_HOURS] is a constant until the shop's settings have it (`preShipmentFeedbackHours`, phase 4); every order
 * keeps the deadline it was given when its photos were sent (`OrderPhotosSent.feedbackDeadline`).
 */
object PreShipmentPolicy {

    /** How long the customer has to answer the photos: after that they count as approved and the order may ship. */
    const val FEEDBACK_HOURS = 24

    /** The most photos and videos one batch of an order may have. */
    const val MAX_PHOTOS = 20
}
