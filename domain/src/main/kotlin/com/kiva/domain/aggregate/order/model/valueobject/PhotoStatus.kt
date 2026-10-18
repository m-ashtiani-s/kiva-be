package com.kiva.domain.aggregate.order.model.valueobject

/**
 * Where the photos before shipping are (the contract's `PreShipmentMedia.status`), a small machine of its own beside the
 * order's status (roadmap section 7.3):
 *
 * ```
 * WAITING ── sent ──→ SENT ── the customer approves, or the deadline passes ──→ APPROVED
 * SENT, APPROVED ── a change request (phase 3) ──→ CHANGE_REQUESTED ── resolved ──→ WAITING (new photos)
 * ```
 */
enum class PhotoStatus {
    /** Nothing sent yet (the operator may have a draft the customer doesn't see). */
    WAITING,

    /** Sent to the customer, waiting for their answer until the deadline. */
    SENT,

    /** The customer said «همونه!», or didn't answer in time: the order may ship. */
    APPROVED,

    /** The customer asked for a change (phase 3), until the operator resolves it. */
    CHANGE_REQUESTED,
}
