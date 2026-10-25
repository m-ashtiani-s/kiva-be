package com.kiva.domain.aggregate.returnrequest.model.policy

import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.APPROVED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.CLOSED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.PICKUP_SCHEDULED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.RECEIVED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.REFUNDED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.REJECTED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.REQUESTED

/**
 * One place for «from this status, where to» of a return (roadmap section 12.3): the return itself, the panel's
 * `allowedTransitions` and the tests all ask here.
 *
 * | status now | `allowedTransitions` |
 * |---|---|
 * | REQUESTED | APPROVED, REJECTED |
 * | APPROVED, PICKUP_SCHEDULED | PICKUP_SCHEDULED, RECEIVED, CLOSED |
 * | RECEIVED, no refund open | REFUNDED, CLOSED (REFUNDED: «open a refund with its returnId») |
 * | RECEIVED, a refund PENDING or PROCESSING | none: both steps wait for that refund |
 * | REJECTED, REFUNDED, CLOSED | none |
 */
object ReturnTransitionPolicy {

    /** Where a return in [status] may go; [hasOpenRefund] is whether a refund of it is PENDING or PROCESSING. */
    fun allowedFrom(status: ReturnStatus, hasOpenRefund: Boolean): Set<ReturnStatus> = when (status) {
        REQUESTED -> setOf(APPROVED, REJECTED)
        APPROVED, PICKUP_SCHEDULED -> setOf(PICKUP_SCHEDULED, RECEIVED, CLOSED)
        RECEIVED -> if (hasOpenRefund) emptySet() else setOf(REFUNDED, CLOSED)
        REJECTED, REFUNDED, CLOSED -> emptySet()
    }
}
